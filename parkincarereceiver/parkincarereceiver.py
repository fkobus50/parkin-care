import sqlite3
import struct
import threading
import queue
import configparser
from datetime import datetime, timezone
from paho.mqtt import client as mqtt_client
import time
from collections import defaultdict
import json
import jwt  # PyJWT

config = configparser.ConfigParser()
config.read('config.ini')

BROKER = config['MQTT']['BROKER']
PORT = int(config['MQTT']['PORT'])
USERNAME = config['MQTT']['USERNAME']
PASSWORD = config['MQTT']['PASSWORD']
TOPIC = config['MQTT']['TOPIC']

PATIENT_ID = config['APP']['PATIENT_ID']
DB_FILE = config['APP'].get('DB_FILE')

AUDIO_TOPIC = TOPIC + "/audio"

data_queue = queue.Queue()

# Liczniki wiadomości wg typu sensora
msg_counters = defaultdict(int)
msg_counters_lock = threading.Lock()

medicine_history_added = 0
medicine_history_updated = 0

# Kolejka do asynchronicznego zapisu do bazy
save_queue = queue.Queue(maxsize=10000)

def safe_put_save_queue(item):
    try:
        save_queue.put(item, timeout=5)
        warn_queue()
    except queue.Full:
        log_message(f"[ERROR] save_queue pełna! Odrzucono dane: {str(item)[:100]}")

def warn_queue():
    qlen = save_queue.qsize()
    if not hasattr(warn_queue, 'last_warned_len'):
        warn_queue.last_warned_len = 0
    if qlen > 1000 and (qlen // 100) != (warn_queue.last_warned_len // 100):
        log_message(f"[WARN] save_queue bardzo długa: {qlen} elementów!")
        warn_queue.last_warned_len = qlen

known_sensors = ['accelerometer','gyroscope','barometer','heart_rate','light','audio']

def init_db():
    conn = sqlite3.connect(DB_FILE, timeout=10)
    conn.execute('PRAGMA journal_mode=WAL;')
    c = conn.cursor()
    # Historia leków
    c.execute('''
        CREATE TABLE IF NOT EXISTS medicine_history (
            id TEXT PRIMARY KEY,
            type TEXT NOT NULL,
            scheduled_date TEXT NOT NULL,
            taken INTEGER NOT NULL DEFAULT 0,
            shown INTEGER NOT NULL DEFAULT 0,
            execution_date TEXT,
            notification_date TEXT,
            dose REAL NOT NULL,
            medicine_id TEXT NOT NULL,
            medicine_name TEXT NOT NULL,
            api_token TEXT NOT NULL
        )
    ''')
    c.execute('CREATE INDEX IF NOT EXISTS idx_medhist_api_scheduled ON medicine_history (api_token, scheduled_date)')
    c.execute('CREATE INDEX IF NOT EXISTS idx_medhist_medicine ON medicine_history (medicine_id)')
    c.execute('CREATE INDEX IF NOT EXISTS idx_medhist_scheduled ON medicine_history (scheduled_date)')
    c.execute('CREATE INDEX IF NOT EXISTS idx_medhist_taken ON medicine_history (taken)')
    c.execute('CREATE INDEX IF NOT EXISTS idx_medhist_execution ON medicine_history (execution_date)')

    # Akcelerometr
    c.execute('''
        CREATE TABLE IF NOT EXISTS accelerometer (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            patient_id TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            x REAL, y REAL, z REAL
        )
    ''')
    c.execute('CREATE INDEX IF NOT EXISTS idx_accel_patient_time ON accelerometer (patient_id, timestamp)')

    # Żyroskop
    c.execute('''
        CREATE TABLE IF NOT EXISTS gyroscope (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            patient_id TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            x REAL, y REAL, z REAL
        )
    ''')
    c.execute('CREATE INDEX IF NOT EXISTS idx_gyro_patient_time ON gyroscope (patient_id, timestamp)')

    # Tętno
    c.execute('''
        CREATE TABLE IF NOT EXISTS heart_rate (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            patient_id TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            value REAL
        )
    ''')
    c.execute('CREATE INDEX IF NOT EXISTS idx_hr_patient_time ON heart_rate (patient_id, timestamp)')

    # Barometr
    c.execute('''
        CREATE TABLE IF NOT EXISTS barometer (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            patient_id TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            value REAL
        )
    ''')
    c.execute('CREATE INDEX IF NOT EXISTS idx_bar_patient_time ON barometer (patient_id, timestamp)')

    # Światło
    c.execute('''
        CREATE TABLE IF NOT EXISTS light (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            patient_id TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            value REAL
        )
    ''')
    c.execute('CREATE INDEX IF NOT EXISTS idx_light_patient_time ON light (patient_id, timestamp)')

    # Audio
    c.execute('''
        CREATE TABLE IF NOT EXISTS audio (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            patient_id TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            audio_data BLOB
        )
    ''')
    c.execute('CREATE INDEX IF NOT EXISTS idx_audio_patient_time ON audio (patient_id, timestamp)')

    # Fragmenty audio
    c.execute('''
        CREATE TABLE IF NOT EXISTS audio_fragments (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            patient_id TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            audio BLOB
        )
    ''')
    c.execute('CREATE INDEX IF NOT EXISTS idx_audio_fragments_patient_time ON audio_fragments (patient_id, timestamp)')
    conn.commit()
    conn.close()

def save_audio_fragment(patient_id, audio_bytes):
    from datetime import datetime, timezone
    conn = sqlite3.connect(DB_FILE, timeout=10)
    conn.execute('PRAGMA journal_mode=WAL;')
    c = conn.cursor()
    ts = int(datetime.now(timezone.utc).timestamp() * 1000)
    c.execute('''
        INSERT INTO audio_fragments (patient_id, timestamp, audio)
        VALUES (?, ?, ?)
    ''', (patient_id, ts, audio_bytes))
    conn.commit()
    conn.close()
    log_message(f"[DB] Zapisano fragment audio ({len(audio_bytes)} bajtów, ts={ts})")

def parse_sensor_payload(sensor_key, payload):
    sensor_value_count = {
        'accelerometer': 3,
        'gyroscope': 3,
        'barometer': 1,
        'heart_rate': 1,
        'light': 1
    }
    if sensor_key not in sensor_value_count:
        log_message(f"[ERROR] Nieznany sensor_key: {sensor_key}. Payload: {str(payload)[:100]}")
        return None
    value_count = sensor_value_count[sensor_key]
    if len(payload) < 4:
        log_message(f"[ERROR] Payload zbyt krótki dla {sensor_key}. Oczekiwano min. 4 bajty (liczba rekordów), otrzymano {len(payload)} bajtów. Fragment: {str(payload)[:100]}")
        return []
    try:
        sample_count = struct.unpack('<I', payload[:4])[0]
    except Exception as e:
        log_message(f"[ERROR] Nie udało się odczytać liczby rekordów z payloadu dla {sensor_key}: {e}. Fragment: {str(payload)[:100]}")
        return []
    record_size = 8 + 4 * value_count
    expected_len = 4 + sample_count * record_size
    if len(payload) < expected_len:
        log_message(f"[ERROR] Payload niezgodny z oczekiwanym formatem dla {sensor_key}. Oczekiwano {expected_len} bajtów (dla {sample_count} rekordów), otrzymano {len(payload)} bajtów. Fragment: {str(payload)[:100]}")
        return []
    results = []
    offset = 4
    for i in range(sample_count):
        chunk = payload[offset:offset+record_size]
        if len(chunk) < record_size:
            log_message(f"[ERROR] Rekord nr {i} niepełny dla {sensor_key}. Oczekiwano {record_size} bajtów, otrzymano {len(chunk)} bajtów. Fragment: {str(chunk)[:100]}")
            break
        try:
            unpacked = struct.unpack('<q' + 'f'*value_count, chunk)
        except Exception as e:
            log_message(f"[ERROR] Błąd przy rozpakowywaniu rekordu nr {i} dla {sensor_key}: {e}. Fragment: {str(chunk)[:100]}")
            break
        timestamp = unpacked[0]
        values = unpacked[1:]
        results.append((timestamp, *values))
        offset += record_size
    return results

def save_worker():
    conn = sqlite3.connect(DB_FILE, timeout=10)
    conn.execute('PRAGMA journal_mode=WAL;')
    c = conn.cursor()
    import traceback
    while True:
        item = save_queue.get()
        if item is None:
            break
        warn_queue()
        try:
            if item[0] == 'audio':
                _, patient_id, timestamp, audio_bytes = item
                decoded = get_decoded_patient_id(patient_id)
                final_patient_id = decoded if decoded else (patient_id if patient_id else PATIENT_ID)
                c.execute('INSERT INTO audio (patient_id, timestamp, audio_data) VALUES (?, ?, ?)',
                          (final_patient_id, timestamp, audio_bytes))
                conn.commit()
                with msg_counters_lock:
                    msg_counters['audio'] += 1
                log_message(f"[DB] Dodano do bazy 1 rekord audio, patient_id={final_patient_id}, timestamp={timestamp}, bytes={len(audio_bytes)}")
            else:
                sensor_key, patient_id, records = item
                decoded_id = get_decoded_patient_id(patient_id)
                final_patient_id = decoded_id if decoded_id else (patient_id if patient_id else PATIENT_ID)
                valid_records = []
                if sensor_key in ['accelerometer', 'gyroscope']:
                    for r in records:
                        if isinstance(r, (list, tuple)) and len(r) == 4:
                            valid_records.append(r)
                        else:
                            log_message(f"[DB WARN] Nieprawidłowy rekord dla {sensor_key}, patient_id={patient_id}: {r}")
                    if valid_records:
                        c.executemany(f"INSERT INTO {sensor_key} (patient_id, timestamp, x, y, z) VALUES (?, ?, ?, ?, ?)",
                                      [(final_patient_id, ts, x, y, z) for (ts, x, y, z) in valid_records])
                elif sensor_key in ['barometer', 'heart_rate', 'light']:
                    for r in records:
                        if isinstance(r, (list, tuple)) and len(r) == 2:
                            valid_records.append(r)
                        else:
                            log_message(f"[DB WARN] Nieprawidłowy rekord dla {sensor_key}, patient_id={patient_id}: {r}")
                    if valid_records:
                        c.executemany(f"INSERT INTO {sensor_key} (patient_id, timestamp, value) VALUES (?, ?, ?)",
                                      [(final_patient_id, ts, value) for (ts, value) in valid_records])
                else:
                    log_message(f"[DB WARN] Nieznany sensor_key przy wstawianiu: {sensor_key}")
                conn.commit()
                with msg_counters_lock:
                    msg_counters[sensor_key] += len(valid_records)
                log_message(f"[DB] Dodano do bazy {len(valid_records)} rekordów dla {sensor_key}, patient_id={final_patient_id}")
        except Exception as e:
            tb = traceback.format_exc()
            log_message(f"[DB ERROR] Błąd przy zapisie do bazy dla {item}: {e} ({type(e).__name__})\n{tb}")
    conn.close()

def save_sensor_data(sensor_key, patient_id, records):
    conn = sqlite3.connect(DB_FILE, timeout=10)
    conn.execute('PRAGMA journal_mode=WAL;')
    c = conn.cursor()
    decoded_id = get_decoded_patient_id(patient_id)
    final_patient_id = decoded_id if decoded_id else (patient_id if patient_id else PATIENT_ID)
    if sensor_key in ['accelerometer', 'gyroscope']:
        for rec in records:
            if not (isinstance(rec, (list, tuple)) and len(rec) == 4):
                log_message(f"[DB WARN] Nieprawidłowy rekord w save_sensor_data dla {sensor_key}: {rec}")
                continue
            timestamp, x, y, z = rec
            c.execute(f"INSERT INTO {sensor_key} (patient_id, timestamp, x, y, z) VALUES (?, ?, ?, ?, ?)",
                      (final_patient_id, timestamp, x, y, z))
    elif sensor_key in ['barometer', 'heart_rate', 'light']:
        for rec in records:
            if not (isinstance(rec, (list, tuple)) and len(rec) == 2):
                log_message(f"[DB WARN] Nieprawidłowy rekord w save_sensor_data dla {sensor_key}: {rec}")
                continue
            timestamp, value = rec
            c.execute(f"INSERT INTO {sensor_key} (patient_id, timestamp, value) VALUES (?, ?, ?)",
                      (final_patient_id, timestamp, value))
    conn.commit()
    conn.close()
    with msg_counters_lock:
        msg_counters[sensor_key] += len(records)

def save_audio(patient_id, timestamp, audio_bytes):
    conn = sqlite3.connect(DB_FILE, timeout=10)
    conn.execute('PRAGMA journal_mode=WAL;')
    c = conn.cursor()
    decoded = get_decoded_patient_id(patient_id)
    final_patient_id = decoded if decoded else (patient_id if patient_id else PATIENT_ID)
    c.execute('INSERT INTO audio (patient_id, timestamp, audio_data) VALUES (?, ?, ?)',
              (final_patient_id, timestamp, audio_bytes))
    conn.commit()
    conn.close()
    with msg_counters_lock:
        msg_counters['audio'] += 1

log_lines_counter = 0
log_lines_timestamps = []  # lista timestampów logów z zegarka (tylko z datą)
log_lines_contents = []    # lista treści logów z zegarka (tylko z datą)
log_lines_all = []         # lista wszystkich linii logów z zegarka (tu: (ts, text, dt_str lub None))

patient_id_cache = {}

def get_decoded_patient_id(token):
    if not token:
        log_message(f"[JWT] Brak tokenu do dekodowania (token jest None lub pusty).")
        return None

    # Jeśli mamy w cache, zwracamy wartość bez próby cięcia tokenu jeśli to nie string
    try:
        if token in patient_id_cache:
            cached = patient_id_cache[token]
            token_preview = token[:16] + '...' if isinstance(token, str) else str(token)
            log_message(f"[CACHE] Użyto zdekodowanego patient_id z cache dla tokenu: {token_preview} -> {cached}")
            return cached
    except Exception:
        # W razie nietypowych kluczy w cache po prostu kontynuujemy
        pass
    try:
        payload = jwt.decode(token, options={"verify_signature": False})
        patient_id = payload.get("sub")
        patient_id_cache[token] = patient_id
        token_preview = token[:16] + '...' if isinstance(token, str) else str(token)
        log_message(f"[JWT] Zdekodowano token, patient_id(sub)={patient_id}, dodano do cache (token: {token_preview})")
        return patient_id
    except Exception as e:
        token_preview = token[:16] + '...' if isinstance(token, str) else str(token)
        log_message(f"[JWT ERROR] Błąd dekodowania tokenu: {e}, token: {token_preview}")
        return None

def on_message(client, userdata, msg):
    from datetime import datetime
    global log_lines_counter, log_lines_timestamps, log_lines_contents, log_lines_all
    global medicine_history_added, medicine_history_updated
    topic = msg.topic

    # Obsługa logów z zegarka: parkincare/sensordata/*/logs/entry
    if topic.startswith("parkincare/sensordata/") and topic.endswith("/logs/entry"):
        try:
            try:
                text = msg.payload.decode('utf-8')
            except Exception:
                text = str(msg.payload)
            import re
            m = re.match(r"(\d{4}\.\d{2}\.\d{2} \d{2}:\d{2}:\d{2}\.\d{3})", text)
            dt_str = None
            ts = int(time.time())
            if m:
                dt_str = m.group(1)
                try:
                    dt = datetime.strptime(dt_str, "%Y.%m.%d %H:%M:%S.%f")
                    ts = int(dt.timestamp())
                    log_lines_timestamps.append(ts)
                    log_lines_contents.append(text.strip())
                except Exception:
                    log_message(f"[ERROR] Nie udało się sparsować daty z logu zegarka: '{text.strip()}'")
            else:
                log_message(f"[ERROR] Linia logu zegarka bez daty na początku: '{text.strip()}'")
            log_lines_all.append((time.time(), text.strip(), dt_str))
            log_filename = f"parkincare_log_{dt_str[:10].replace('.', '-') if dt_str else datetime.now().strftime('%Y-%m_%d')}.txt"
            with open(log_filename, 'a', encoding='utf-8') as f:
                f.write(text.rstrip('\n') + '\n')
            lines_added = text.count('\n') + (0 if text.endswith('\n') else 1)
            with msg_counters_lock:
                global log_lines_counter
                log_lines_counter += lines_added
        except Exception as e:
            log_message(f"[ERROR] Błąd zapisu logu zegarka: {e}")
        return  # nie przetwarzaj dalej

    # Obsługa przyjmowanych leków
    #  - parkincare/sensordata/{apiToken}/medicine/history  (token w ścieżce)
    #  - parkincare/sensordata/medicine/history            (token w payloadzie)
    if topic.startswith("parkincare/sensordata") and topic.endswith("/medicine/history"):
        try:
            try:
                text = msg.payload.decode('utf-8')
            except Exception:
                text = str(msg.payload)
            obj = json.loads(text)
        except Exception as e:
            log_message(f"[ERROR] Nie można sparsować JSON dla medicine/history: {e}. Payload (truncated): {str(msg.payload)[:200]}")
            return
        # preferujemy apiToken z payloadu, a jeśli go brak, spróbujemy pobrać z topicu
        parts = topic.split('/')
        api_token_from_topic = None
        if len(parts) >= 4:
            # parts: ['parkincare','sensordata', ... ]
            candidate = parts[2]
            if candidate and candidate != 'medicine':
                api_token_from_topic = candidate
        api_token = obj.get('apiToken') or api_token_from_topic
        if api_token:
            obj['apiToken'] = api_token
        else:
            log_message(f"[MED WARN] Brak apiToken w payload i w topicie dla medicine/history (topic={topic}). Zapis bez tokenu.")
        med_id = obj.get('id')
        try:
            save_medicine_history_with_retries(obj, max_retries=5, delay=30)
            log_message(f"[MQTT] Zaplanowano asynchroniczny zapis medicine_history id={med_id} (retry up to 5x, 30s)")
        except Exception as e:
            log_message(f"[ERROR] Nie udało się zaplanować zapisu medicine_history id={med_id}: {e}")
        return  # WAŻNE: nie przetwarzaj dalej
    if topic.endswith("/medicine/history"):
        try:
            text = msg.payload.decode('utf-8')
        except Exception:
            text = str(msg.payload)
        log_message(f"[MQTT] Otrzymano wiadomość z topicu '{topic}': {text}")
        return
    try:
        t0 = time.time()
        topic = msg.topic
        parts = topic.split('/')
        sensor_key = next((k for k in known_sensors if any(k in p for p in parts)), None)
        if sensor_key is None:
            sensor_key = parts[-1]
        patient_id = None
        patient_id_short = None
        if len(parts) >= 4 and parts[0] == 'parkincare' and parts[1] == 'sensordata':
            # Jeśli jest ID: parkincare/sensordata/PATIENT_ID/sensor_key
            patient_id = parts[2]
            patient_id_short = patient_id[:6]
        else:
            # Brak ID: parkincare/sensordata/sensor_key
            patient_id = None
            patient_id_short = None
        # Skrócony log MQTT
        # Wycinamy patient_id z topicu i zamieniamy na skróconą wersję z wielokropkiem
        if patient_id_short:
            topic_short = topic.replace(patient_id, patient_id_short + "...")
            log_message(f"[MQTT] Odebrano topic: {topic_short}, sensor_key: {sensor_key}")
        else:
            log_message(f"[MQTT] Odebrano topic: {topic}, sensor_key: {sensor_key}")
        payload_for_parsing = msg.payload
        audio_bytes = None
        text = None
        try:
            text = msg.payload.decode('utf-8')
        except Exception:
            text = None
        json_objs = []
        ndjson_mode = False
        if text is not None:
            try:
                obj = json.loads(text)
                json_objs = [obj]
            except json.JSONDecodeError as e:
                if '\n' in text or 'Extra data' in str(e):
                    lines = [l for l in text.splitlines() if l.strip()]
                    if len(lines) >= 2:
                        ndjson_mode = True
                        for l in lines:
                            try:
                                json_objs.append(json.loads(l))
                            except Exception as ex:
                                log_message(f"[NDJSON] Błąd parsowania linii: {ex}, linia: {l[:100]}")
                    else:
                        log_message(f"[WARN] JSONDecodeError, ale nie NDJSON: {e}")
                else:
                    log_message(f"[WARN] JSONDecodeError: {e}")
        is_batch = False
        if ndjson_mode or (text is not None and len([l for l in text.splitlines() if l.strip()]) >= 2):
            is_batch = True
        elif any(s in parts[-1] for s in ['_batch', '.bin']):
            is_batch = True
        batch_detected = is_batch
        if ndjson_mode:
            records = []
            for obj in json_objs:
                pid = patient_id
                for k in ('patient_id', 'patientId', 'id'):
                    if k in obj:
                        pid = str(obj[k])
                        break
                pf = None
                if 'payload' in obj:
                    import base64
                    try:
                        pf = base64.b64decode(obj['payload'].replace('\n','').strip())
                    except Exception as e:
                        log_message(f"[ERROR] Nie udało się zdekodować payload z base64: {e}")
                if pf is not None:
                    if sensor_key == 'audio':
                        timestamp = int(datetime.now(timezone.utc).timestamp() * 1000)
                        safe_put_save_queue(('audio', pid, timestamp, pf))
                    else:
                        recs = parse_sensor_payload(sensor_key, pf)
                        if recs:
                            records.extend(recs)
                else:
                    if sensor_key in ['accelerometer', 'gyroscope'] and all(k in obj for k in ('timestamp','x','y','z')):
                        records.append((obj['timestamp'], obj['x'], obj['y'], obj['z']))
                    elif all(k in obj for k in ('timestamp','value')):
                        records.append((obj['timestamp'], obj['value']))
            if sensor_key == 'audio':
                pass # już obsłużone wyżej
            elif records:
                log_message(f"[DEBUG] Rozpakowano {len(records)} rekordów z payloadu dla {sensor_key} (BATCH)")
                safe_put_save_queue((sensor_key, patient_id, records))
                warn_queue()
            else:
                log_message(f"[WARN] NDJSON: Brak rekordów do zapisania dla {sensor_key}, patient_id={patient_id}")
            return
        # tryb pojedynczego JSON lub binarny
        if text is not None and json_objs:
            obj = json_objs[0]
            for k in ('patient_id', 'patientId', 'id'):
                if k in obj:
                    patient_id = str(obj[k])
                    break
            if 'payload' in obj:
                import base64
                try:
                    payload_for_parsing = base64.b64decode(obj['payload'].replace('\n','').strip())
                    if sensor_key == 'audio':
                        audio_bytes = payload_for_parsing
                except Exception as e:
                    log_message(f"[ERROR] Nie udało się zdekodować payload z base64: {e}")
            else:
                if all(k in obj for k in ('timestamp','x','y','z')):
                    records = [(obj['timestamp'], obj['x'], obj['y'], obj['z'])]
                    log_message(f"[DEBUG] Rozpakowano 1 rekord z payloadu dla {sensor_key} (SINGLE)")
                    safe_put_save_queue((sensor_key, patient_id, records))
                    return
                elif all(k in obj for k in ('timestamp','value')):
                    records = [(obj['timestamp'], obj['value'])]
                    log_message(f"[DEBUG] Rozpakowano 1 rekord z payloadu dla {sensor_key} (SINGLE)")
                    safe_put_save_queue((sensor_key, patient_id, records))
                    return
        if sensor_key == 'audio':
            timestamp = int(datetime.now(timezone.utc).timestamp() * 1000)
            if audio_bytes is not None:
                log_message(f"[DEBUG] Kolejka audio: patient_id={patient_id}, timestamp={timestamp}, bytes={len(audio_bytes)} (BIN)")
                safe_put_save_queue(('audio', patient_id, timestamp, audio_bytes))
            else:
                log_message(f"[DEBUG] Kolejka audio: patient_id={patient_id}, timestamp={timestamp}, bytes={len(msg.payload)} (RAW)")
                safe_put_save_queue(('audio', patient_id, timestamp, msg.payload))
        elif sensor_key in known_sensors:
            try:
                records = parse_sensor_payload(sensor_key, payload_for_parsing)
                typ = "BATCH" if batch_detected else "SINGLE"
                extra = ""
                if not batch_detected and records and len(records) > 0:
                    # Pobierz timestamp pierwszego rekordu
                    ts = records[0][0]
                    try:
                        dt = datetime.fromtimestamp(ts/1000, tz=timezone.utc)
                        extra = f", pierwszy pomiar: {dt.strftime('%Y-%m-%d %H:%M:%S')}(UTC)"
                    except Exception:
                        extra = f", pierwszy timestamp: {ts}"
                log_message(f"[DEBUG] Rozpakowano {len(records) if records else 0} rekordów z payloadu dla {sensor_key} ({typ}{extra})")
                if records:
                    safe_put_save_queue((sensor_key, patient_id, records))
                else:
                    log_message(f"[WARN] Brak rekordów do zapisania dla {sensor_key}, patient_id={patient_id}")
            except Exception as e:
                log_message(f"[ERROR] Błąd przy rozpakowywaniu payloadu dla {sensor_key}: {e}")
        t1 = time.time()
    except Exception as e:
        log_message(f"Error processing MQTT payload: {e}")

def on_disconnect(client, userdata, rc):
    if rc != 0:
        log_message(f"[MQTT] Utracono połączenie z brokerem MQTT (rc={rc}). Próba ponownego połączenia...")
        while True:
            try:
                time.sleep(5)
                client.reconnect()
                log_message("[MQTT] Ponownie połączono z brokerem MQTT.")
                break
            except Exception as e:
                log_message(f"[MQTT] Błąd przy ponownym łączeniu: {e}")
                continue

def on_connect(client, userdata, flags, rc):
    if rc == 0:
        topic_base = TOPIC.rstrip('/')
        if topic_base:
            sub_filter = topic_base + "/#"
        else:
            sub_filter = "#"
        client.subscribe(sub_filter, qos=1)
        log_message(f"[MQTT] Połączono z brokerem. Subskrypcja topic '{sub_filter}' (patient_id={PATIENT_ID})")
    else:
        log_message(f"[MQTT] Błąd połączenia z brokerem MQTT, rc={rc}")

def stats_reporter():
    global log_lines_counter, log_lines_timestamps, log_lines_contents, log_lines_all
    global medicine_history_added, medicine_history_updated
    while True:
        time.sleep(30)
        now_ts = time.time()
        # Filtruj logi z zegarka odebrane w ostatnich 30 sekundach
        recent_log_all = [(recv_ts, text, dt_str) for recv_ts, text, dt_str in log_lines_all if now_ts - recv_ts <= 30]
        # Najstarsza data z payloadu (dt_str) spośród tych logów
        dt_candidates = [dt_str for _, _, dt_str in recent_log_all if dt_str]
        if dt_candidates:
            # Zamień na datetime i wybierz najstarszą
            try:
                oldest_log_dt = min(datetime.strptime(dt, "%Y.%m.%d %H:%M:%S.%f") for dt in dt_candidates)
                oldest_log_dt_str = oldest_log_dt.strftime('%Y-%m-%d %H:%M:%S')
                # Dodaj pobieranie najnowszego wpisu
                newest_log_dt = max(datetime.strptime(dt, "%Y.%m.%d %H:%M:%S.%f") for dt in dt_candidates)
                newest_log_dt_str = newest_log_dt.strftime('%Y-%m-%d %H:%M:%S')
            except Exception:
                oldest_log_dt_str = dt_candidates[0]  # fallback: pierwszy tekst
                newest_log_dt_str = dt_candidates[-1] if len(dt_candidates) > 1 else dt_candidates[0]
        else:
            oldest_log_dt_str = None
            newest_log_dt_str = None
        with msg_counters_lock:
            if msg_counters or log_lines_counter > 0 or medicine_history_added > 0 or medicine_history_updated > 0:
                log_message("\n[STATYSTYKA ostatnie 30s]")
                for sensor, count in msg_counters.items():
                    log_message(f"  {sensor}: {count}")
                if medicine_history_added > 0 or medicine_history_updated > 0:
                    log_message(f"  medicine_history: dodano {medicine_history_added}, zaktualizowano {medicine_history_updated}")
                if oldest_log_dt_str:
                    log_message(f"  logi z zegarka (linie): {len(recent_log_all)}, najstarszy: {oldest_log_dt_str}, najnowszy: {newest_log_dt_str}")
                else:
                    log_message(f"  logi z zegarka (linie): {len(recent_log_all)}, brak daty w logach")
                log_message("")
                msg_counters.clear()
                log_lines_counter = 0
                medicine_history_added = 0
                medicine_history_updated = 0
            else:
                log_message("\n[STATYSTYKA ostatnie 30s] Brak zapisanych wiadomości.\n")
        # Czyścimy listy po statystykach
        log_lines_timestamps = []
        log_lines_contents = []
        log_lines_all = []

def heartbeat_publisher(client):
    import socket
    from datetime import timezone
    while True:
        try:
            if client.is_connected():
                heartbeat_topic = "parkincare/receiver/heartbeat"
                payload = {
                    "timestamp": int(datetime.now(timezone.utc).timestamp() * 1000),
                    "host": socket.gethostname(),
                    "app": "parkincarereceiver.py"
                }
                client.publish(heartbeat_topic, str(payload), qos=1)
                log_message(f"[HEARTBEAT] Wysłano heartbeat na {heartbeat_topic} o {payload['timestamp']} (host={payload['host']})")
            else:
                log_message("[HEARTBEAT] Pominięto heartbeat: brak połączenia z brokerem MQTT.")
        except Exception as e:
            pass
        time.sleep(10)

def log_message(msg):
    now = datetime.now()
    prefix = now.strftime('%Y.%m.%d %H:%M:%S.%f')[:-3] + ' | '
    try:
        msg_str = str(msg)
    except Exception:
        msg_str = '<unrepresentable message>'
    if len(msg_str) > 1000:
        msg_str = msg_str[:1000] + '... [truncated]'
    log_line = prefix + msg_str.rstrip('\n')

    log_filename = f"receiver_log_{now.strftime('%Y_%m_%d')}.txt"
    with open(log_filename, 'a', encoding='utf-8') as f:
        f.write(log_line + '\n')

    print(log_line)

save_worker_thread = None

def watchdog_save_worker():
    global save_worker_thread
    while True:
        time.sleep(10)
        if save_worker_thread is not None and not save_worker_thread.is_alive():
            log_message("[WATCHDOG] save_worker nie działa! Restartuję wątek zapisu do bazy.")
            save_worker_thread = threading.Thread(target=save_worker, daemon=True)
            save_worker_thread.start()

def upsert_medicine_history(data):
    conn = sqlite3.connect(DB_FILE, timeout=10)
    c = conn.cursor()
    # Konwersja typów
    taken = int(data.get('taken', False))
    shown = int(data.get('shown', False))
    # Dekoduj apiToken jeśli jest dostępny
    api_token = data.get('apiToken')
    decoded_api_token = None
    if api_token:
        decoded_api_token = get_decoded_patient_id(api_token)

    api_token_value = decoded_api_token if decoded_api_token else (api_token if api_token else '')

    c.execute('''
        INSERT OR REPLACE INTO medicine_history (
            id, type, scheduled_date, taken, shown, execution_date, notification_date, dose, medicine_id, medicine_name, api_token
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ''', (
        data['id'],
        data.get('type', 'MedicineHistory'),
        data['scheduledDate'],
        taken,
        shown,
        data.get('executionDate'),
        data.get('notificationDate'),
        data['dose'],
        data['medicineId'],
        data['medicineName'],
        api_token_value
    ))
    conn.commit()
    conn.close()

def medicine_history_exists(med_id):
    conn = sqlite3.connect(DB_FILE, timeout=10)
    c = conn.cursor()
    c.execute('SELECT 1 FROM medicine_history WHERE id=?', (med_id,))
    exists = c.fetchone() is not None
    conn.close()
    return exists

def save_medicine_history_with_retries(data, max_retries=5, delay=30):
    """
    Zapisuje wpis medicine_history z ponawianiem w oddzielnym wątku.
    - data: dict z danymi medicine_history
    - max_retries: ile razy spróbować (domyślnie 5)
    - delay: opóźnienie między próbami w sekundach (domyślnie 30)

    Funkcja uruchamia daemon thread i natychmiast zwraca.
    Inkrementuje globalne liczniki medicine_history_added / medicine_history_updated
    dopiero po udanym zapisie.
    """
    def worker():
        global medicine_history_added, medicine_history_updated
        med_id = data.get('id')
        attempts = 0
        existed = False
        try:
            existed = medicine_history_exists(med_id)
        except Exception as e:
            log_message(f"[MED] Błąd podczas sprawdzania istnienia medicine_history id={med_id}: {e}")
            existed = False
        while attempts < max_retries:
            try:
                upsert_medicine_history(data)
                # Sukces
                with msg_counters_lock:
                    if existed:
                        medicine_history_updated += 1
                    else:
                        medicine_history_added += 1
                log_message(f"[MQTT] Zapisano medicine_history id={med_id} (próba {attempts+1}/{max_retries})")
                return
            except Exception as e:
                attempts += 1
                log_message(f"[MED ERROR] Błąd zapisu medicine_history id={med_id}, próba {attempts}/{max_retries}: {e}")
                if attempts >= max_retries:
                    log_message(f"[MED ERROR] Wyczerpane próby zapisu medicine_history id={med_id}. Odrzucono.")
                    return
                time.sleep(delay)

    t = threading.Thread(target=worker, daemon=True)
    t.start()

def main():
    global save_worker_thread
    init_db()
    threading   .Thread(target=stats_reporter, daemon=True).start()
    save_worker_thread = threading.Thread(target=save_worker, daemon=True)
    save_worker_thread.start()
    threading.Thread(target=watchdog_save_worker, daemon=True).start()

    client = mqtt_client.Client()
    client.username_pw_set(USERNAME, PASSWORD)
    client.tls_set(
        ca_certs="ca.crt",
        certfile="client.crt",
        keyfile="client.key",
        tls_version=2  # odpowiada ssl.PROTOCOL_TLSv1_2
    )
    client.on_message = on_message
    client.on_disconnect = on_disconnect
    client.on_connect = on_connect
    try:
        client.connect(BROKER, PORT)
    except Exception as e:
        log_message(f"[BŁĄD] Nie można połączyć się z serwerem MQTT {BROKER}:{PORT}: {e}")
        log_message("Aplikacja zostanie zamknięta.")
        return

    threading.Thread(target=heartbeat_publisher, args=(client,), daemon=True).start()

    log_message(f"Listening on MQTT {BROKER}:{PORT}... (patient_id={PATIENT_ID})")
    client.loop_forever()

if __name__ == '__main__':
    main()
