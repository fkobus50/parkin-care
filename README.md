# ParkinCare

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Kotlin](https://img.shields.io/badge/Kotlin-Wear%20OS-blueviolet.svg)](https://kotlinlang.org/)
[![Python](https://img.shields.io/badge/Python-Backend-blue.svg)](https://www.python.org/)

**ParkinCare** is an open-source, continuous patient monitoring system designed for individuals with Parkinson’s disease. It consists of a Wear OS smartwatch application for multi-modal data acquisition and a Python-based server for asynchronous data storage and analysis. 

The system provides 24/7 data collection of digital biomarkers in home environments, ensuring reliable operation independently of internet connectivity by utilizing a robust local-storage fallback mechanism.

## Features

* **Continuous Data Collection (24/7):** Passive acquisition of multimodal sensor data (accelerometer, gyroscope, barometer, heart rate, light sensor) and experimental audio recordings.
* **Offline Resilience:** Hard-coded data buffering and local queuing mechanisms. Data is saved locally in a Room database when the network is unavailable and automatically synchronized via MQTT once connectivity is restored.
* **Medication Management:** Integration with external APIs to fetch medication schedules and report patient adherence (confirming or rejecting intake events) directly from the smartwatch.
* **Multi-Patient Support:** Supports seamless switching between patient profiles via external API integration, allowing a single wearable device to serve multiple study participants without manual re-pairing or device resetting.
* **Reliable Backend Persistence:** A lightweight, cross-platform Python receiver utilizing worker threads to ingest MQTT streams and store them granularly into a structured SQLite3 database.

## Repository Structure

The repository is organized into two main directories (monorepo):

```text
parkin-care/
├── src/
│   ├── parkincare/                 # Wear OS client application (Kotlin / Gradle)
│   │   ├── app/                    # Application source code, Compose UI, and background services
│   │   │   ├── build.gradle.kts    # Module-level Gradle build configuration
│   │   │   └── src/                # Kotlin source files and resources
│   │   ├── gradle/                 # Gradle wrapper files
│   │   ├── build.gradle.kts        # Root-level build configuration
│   │   ├── gradle.properties       # Project-wide Gradle configuration properties
│   │   ├── gradlew                 # Gradle wrapper script for Linux/macOS
│   │   ├── gradlew.bat             # Gradle wrapper script for Windows
│   │   └── settings.gradle.kts     # Project settings and module definitions
│   └── parkincarereceiver/         # Python backend application
│       ├── receiver.py             # Main MQTT receiver script
│       └── requirements.txt        # Python dependencies
├── README.md                       # Project documentation and quick start guide
└── LICENSE.txt                     # MIT License
```

### Wear OS Application (`parkincare`)
* **IDE:** Android Studio (latest version recommended)
* **Android SDK:** 
  * Compile SDK: API 35 (Android 15)
  * Target SDK: API 28 (Android 9.0)
  * Minimum SDK: API 28 (Android 9.0)
* **Hardware:** Smartwatch running Wear OS (Wear OS 2.0 / Android 9.0 or higher) or a Wear OS emulator
* **Key Dependencies:** JVM 11, Kotlin, Jetpack Compose for Wear OS, Horologist, Room, WorkManager, HiveMQ MQTT Client.

### Python Receiver (`parkincarereceiver`)
* **Environment:** PC/Server running Linux, Windows, or macOS
* **Runtime:** Python 3.10 or higher
* **Key Dependencies:**
  * `paho-mqtt` (>= 1.6.0)
  * `PyJWT` (>= 2.6.0)
  * `sqlite3` (Python Standard Library)

## Installation and Setup

### 1. Setting up the Backend Receiver (`parkincarereceiver`)
The backend is responsible for receiving MQTT streams and persisting them to a local SQLite database.

1. Navigate to the receiver directory:
   ```bash
   cd parkincarereceiver
   ```
2. Create and activate a virtual environment (optional but recommended):
   ```bash
   python -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   ```
3. Install dependencies (make sure you create a `requirements.txt` with `paho-mqtt` and `PyJWT`):
   ```bash
   pip install -r requirements.txt
   ```
4. Start the receiver:
   ```bash
   python receiver.py
   ```
*The receiver will start listening for MQTT connections on the configured port and automatically create the SQLite3 database file if it does not exist.*

### 2. Setting up the Wear OS Client (`parkincare`)
1. Open **Android Studio**.
2. Select **File > Open...** and navigate to the `parkincare` directory.
3. Wait for Gradle to sync the project and download all necessary dependencies.
4. Connect your Wear OS smartwatch via Wi-Fi debugging or USB.
5. Build and run the `app` module on your wearable device.

## Usage Guide

1. **Start the server:** Ensure the Python receiver is running on the host machine.
2. **Launch the Wear OS app:** Open ParkinCare on the smartwatch.
3. **Configuration:** Use the smartwatch UI to toggle specific sensors (e.g., accelerometer, HR) based on study requirements.
4. **Monitoring Status:** The main dashboard on the watch provides a glanceable summary:
   * Active patient ID
   * Server connectivity status (MQTT connection state)
   * Storage mode (Live streaming vs. Local buffering)
   * Device memory usage
5. **Medication Logging:** When a medication alert triggers, use the interface to either confirm or reject the intake.

## Disclaimer / Limitations
ParkinCare is designed strictly as an **academic research framework and data-collection tool**. It is **not** a certified medical device and has not undergone medical-grade safety compliance testing required for routine clinical diagnostics.

## License
This project is licensed under the [MIT License](LICENSE).

## Contact & Citation
For technical support or questions regarding the codebase, please contact:
**Filip Kobus** - filip.kobus01@student.wat.edu.pl

*If you use ParkinCare in your research, please cite our SoftwareX paper (citation details will be updated upon publication).*
