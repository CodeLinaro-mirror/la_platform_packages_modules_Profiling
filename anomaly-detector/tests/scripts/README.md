# How to Apply Anomaly Detection Rules on your test Device

This guide explains how to use the `generate_anomaly_rules.py` script to update
the anomaly detection rules on an Android device.

## Prerequisites

Before you begin, ensure you have the following tools installed and available in
your system's `PATH`:

1.  **`protoc`**:

    The protobuf compiler. You can find installation instructions on the
    [official documentation](https://protobuf.dev/installation/).

2.  **`adb`**:

    The Android Debug Bridge. You should have this if you are running inside
    Android tree after setting up the environment. Otherwise, it is included
    with
    [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools).

3.  **Python 3**:

    Along with the `PyYAML` library. You can install it via pip: `pip install
    PyYAML`

## Steps to Push Rules

The script provides a convenient `--push-to-device` flag that automates the
entire process of generating, pushing, and applying the new rules.

1.  **Connect Your Device**:

    Ensure your Android device is connected to your computer and that `adb` can
    recognize it.

2.  **Run the Script**:

    Execute the `generate_anomaly_rules.py` script with the `--push-to-device`
    flag and specify your YAML configuration file with the `-c` flag.

    You can use the provided `rules_example.yaml` as a starting point and modify
    it with the rule you want to use.

    ```sh
    ./generate_anomaly_rules.py -c rules_example.yaml --push-to-device
    ```

    If the script has trouble finding the `.proto` file definition, you can
    explicitly provide the path to the directory containing the `com` package
    using the `--proto-path` argument:

    ```sh
    ./generate_anomaly_rules.py -c rules_example.yaml --push-to-device --proto-path /path/to/your/proto/root
    ```

3.  **Wait for Reboot**:

    After the device reboots, the anomaly detection service will be running with
    your updated rules.
