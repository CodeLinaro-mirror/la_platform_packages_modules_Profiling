#!/usr/bin/env python3
#
#  Copyright (C) 2026 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import yaml

RULE_KEY_PREFIX = "android.os.profiling.anomaly.Rule."

# --- Validation Data ---

# LINT.IfChange(supported_condition_types)
SUPPORTED_CONDITION_TYPES = {
    "binder_spam": {
        "params": {
            "binder_interface_name": "string",
            "binder_method_name": "string",
            "binder_call_limit": "int",
            "binder_call_interval_millis": "long",
        }
    }
}
# LINT.ThenChange(/anomaly-detector/framework/java/android/os/profiling/anomaly/RuleInternal.java:supported_condition_types)

# LINT.IfChange(supported_actions)
SUPPORTED_ACTIONS = {1: "ACTION_TYPE_LOG"}
# LINT.ThenChange(/anomaly-detector/framework/java/android/os/profiling/anomaly/RuleInternal.java:supported_actions)

# --- Text Proto Generation ---


def generate_textproto_from_yaml(config):
  """Parses the YAML config, validates it, and returns a textproto string."""
  try:
    data = yaml.safe_load(config)
  except yaml.YAMLError as e:
    print(
        f"Error: Invalid YAML format in the config file.\n  {e}",
        file=sys.stderr,
    )
    sys.exit(1)

  if not data or "rules" not in data or not isinstance(data["rules"], list):
    print(
        "Error: YAML must contain a top-level list named 'rules'.",
        file=sys.stderr,
    )
    sys.exit(1)

  ruleset_parts = []
  for i, rule_config in enumerate(data["rules"]):
    try:
      validate_rule(rule_config)
      rule_textproto = create_rule_textproto(rule_config)
      ruleset_parts.append(f"rules: {{\n{rule_textproto}\n}}")
    except (ValueError, KeyError) as e:
      print(
          f"Error in rule #{i+1} (name: '{rule_config.get('name', 'N/A')}'):\n"
          f"  {e}",
          file=sys.stderr,
      )
      sys.exit(1)
  return "\n".join(ruleset_parts)


# --- Validation Logic ---


def validate_rule(rule):
  """Validates a single rule dictionary from the parsed YAML."""
  # Check required top-level keys
  for key in ["name", "condition_type", "anomaly_actions", "params"]:
    if key not in rule:
      raise KeyError(f"Missing required key: '{key}'")

  condition_type = rule["condition_type"]
  if condition_type not in SUPPORTED_CONDITION_TYPES:
    raise ValueError(
        f"Unsupported condition_type: '{condition_type}'. "
        f"Supported types are: {', '.join(SUPPORTED_CONDITION_TYPES.keys())}"
    )

  # Validate anomaly actions
  if not isinstance(rule["anomaly_actions"], list):
    raise ValueError("'anomaly_actions' must be a list.")
  for action in rule["anomaly_actions"]:
    if action not in SUPPORTED_ACTIONS:
      supported_actions_str = ", ".join(
          f"{k} ({v})" for k, v in SUPPORTED_ACTIONS.items()
      )
      raise ValueError(
          f"Unsupported action: '{action}'. Supported actions are:"
          f" {supported_actions_str}"
      )

  # Validate parameters for the given condition type
  expected_params = SUPPORTED_CONDITION_TYPES[condition_type]["params"]
  provided_params = rule["params"]

  if not isinstance(provided_params, dict):
    raise ValueError("'params' must be a dictionary/map.")

  # Check for missing parameters
  for param_name in expected_params:
    if param_name not in provided_params:
      raise KeyError(
          f"Missing required parameter for '{condition_type}': '{param_name}'"
      )

  # Check for unknown parameters and correct value types
  for param_name, param_value in provided_params.items():
    if param_name not in expected_params:
      raise KeyError(
          f"Unknown parameter for '{condition_type}': '{param_name}'"
      )

    if (
        not isinstance(param_value, dict)
        or "type" not in param_value
        or "value" not in param_value
    ):
      raise ValueError(
          f"Parameter '{param_name}' must be a dictionary with 'type' and"
          " 'value' keys."
      )

    expected_type = expected_params[param_name]
    provided_type = param_value["type"]
    if provided_type != expected_type:
      raise ValueError(
          f"For parameter '{param_name}', expected type '{expected_type}' but"
          f" got '{provided_type}'."
      )

    # Validate the value's actual type against the declared type
    provided_value = param_value["value"]
    py_type_map = {
        "string": str,
        "int": int,
        "long": int,
        "bool": bool,
        "double": (float, int),  # Allow int for float/double types
        "float": (float, int),
    }

    expected_py_type = py_type_map.get(expected_type)
    if expected_py_type and not isinstance(provided_value, expected_py_type):
      raise ValueError(
          f"For parameter '{param_name}', the value '{provided_value}' "
          f"has the wrong Python type for the declared type '{expected_type}'."
      )


# --- Text Proto Object Creation Logic ---


def create_rule_textproto(rule):
  """Creates a RuleProto textproto message from a single validated rule."""
  parts = []
  parts.append(f'  name: "{rule["name"]}"')
  parts.append(f'  condition_type: "{RULE_KEY_PREFIX}{rule["condition_type"]}"')

  for action in rule["anomaly_actions"]:
    parts.append(f"  anomaly_actions: {action}")

  for param_name, param_details in rule["params"].items():
    param_key = f"{RULE_KEY_PREFIX}{param_name}"
    param_type = param_details["type"]
    param_value = param_details["value"]

    # In textproto, strings need to be quoted and bools need to be lowercase.
    value_str = (
        f'"{param_value}"'
        if param_type == "string"
        else str(param_value).lower()
        if param_type == "bool"
        else str(param_value)
    )

    param_str = (
        f"  rule_condition {{\n"
        f'    key: "{param_key}"\n'
        f"    value {{ {param_type}_value: {value_str} }}\n"
        f"  }}"
    )
    parts.append(param_str)

  return "\n".join(parts)


# --- Push Rules to Device ---


def push_to_device_and_cleanup(output_file):
  """Pushes the generated file to the device and cleans up."""
  device_path = "/data/system/anomaly_service/rules.pb"
  print(f"\nAttempting to push '{output_file}' to '{device_path}' on device...")

  try:
    # 1. ADB Root
    print("  Running: adb root")
    subprocess.run(["adb", "root"], check=True, capture_output=True, text=True)

    # 2. ADB Push
    print(f"  Running: adb push {output_file} {device_path}")
    subprocess.run(
        ["adb", "push", output_file, device_path],
        check=True,
        capture_output=True,
        text=True,
    )
    print("  Successfully pushed to device.")

    # 3. ADB Reboot
    print("  Running: adb reboot")
    subprocess.run(
        ["adb", "reboot"], check=True, capture_output=True, text=True
    )
    print("  Device is rebooting to apply changes.")

  except FileNotFoundError:
    print(
        "\nError: 'adb' command not found. Is Android SDK Platform-Tools"
        " installed and in your PATH?",
        file=sys.stderr,
    )
    sys.exit(1)
  except subprocess.CalledProcessError as e:
    print(
        f"\nError: An adb command failed. (Is 'adb root' enabled?)",
        file=sys.stderr,
    )
    print(f"  Command: {' '.join(e.cmd)}", file=sys.stderr)
    print(f"  Stderr: {e.stderr.strip()}", file=sys.stderr)
    sys.exit(1)
  finally:
    # 2. Cleanup local file
    if os.path.exists(output_file):
      print(f"  Cleaning up local file: {output_file}")
      os.remove(output_file)


# --- Main Execution ---

if __name__ == "__main__":
  parser = argparse.ArgumentParser(
      description=(
          "Generate a RuleSetProto binary proto from a YAML config file.\n"
          "This script uses `protoc` to encode a text proto representation."
      ),
      formatter_class=argparse.RawTextHelpFormatter,
  )
  parser.add_argument(
      "-c",
      "--config",
      required=True,
      type=argparse.FileType("r", encoding="UTF-8"),
      help="Path to the input YAML configuration file for the rules.",
  )
  parser.add_argument(
      "-o",
      "--output",
      help=(
          "Path for the output binary file. Optional if --push-to-device is"
          " used."
      ),
  )
  parser.add_argument(
      "--push-to-device",
      action="store_true",
      help=(
          "Push the generated proto file to the device via adb and then remove"
          " the local file."
      ),
  )
  parser.add_argument(
      "--proto-path",
      help=(
          "Path to the directory containing the 'com' package for the proto"
          " definition. If not provided, a default relative path is used."
      ),
  )
  args = parser.parse_args()

  if not args.push_to_device and not args.output:
    parser.error("--output is required when not using --push-to-device.")

  # Find the proto file.
  proto_path = args.proto_path
  if not proto_path:
    # This assumes the relative path of the proto file is:
    # ../../proto/com/android/server/anomaly/AnomalyRules.proto
    script_dir = os.path.dirname(os.path.realpath(__file__))
    proto_path = os.path.normpath(os.path.join(script_dir, "..", "..", "proto"))
  proto_file_rel_path = os.path.join(
      "com", "android", "server", "anomaly", "AnomalyRules.proto"
  )
  proto_file_abs_path = os.path.join(proto_path, proto_file_rel_path)

  if not os.path.exists(proto_file_abs_path):
    print(
        f"Error: Proto file not found at '{proto_file_abs_path}'",
        file=sys.stderr,
    )
    sys.exit(1)

  temp_dir = tempfile.mkdtemp()

  output_file_path = args.output
  if args.push_to_device and not output_file_path:
    output_file_path = os.path.join(temp_dir, "rules.pb")

  try:
    # 1. Generate text proto content from the YAML config.
    textproto_content = generate_textproto_from_yaml(args.config)

    # 2. Use protoc to encode the text proto to a binary proto.
    # The textproto content is passed via stdin to the protoc command.
    message_type = "com.android.server.anomaly.RuleSetProto"
    result = subprocess.run(
        [
            "protoc",
            f"--proto_path={proto_path}",
            f"--encode={message_type}",
            proto_file_abs_path,
        ],
        input=textproto_content.encode("utf-8"),
        capture_output=True,
        check=True,
    )

    with open(output_file_path, "wb") as f:
      f.write(result.stdout)

    print(
        "Successfully generated RuleSetProto binary message at:"
        f" {output_file_path}"
    )

    if args.push_to_device:
      push_to_device_and_cleanup(output_file_path)

  except FileNotFoundError:
    print(
        "Error: 'protoc' command not found. Please ensure the protobuf compiler"
        " is installed and in your PATH.",
        file=sys.stderr,
    )
    sys.exit(1)
  except subprocess.CalledProcessError as e:
    print(
        "Error: `protoc --encode` failed. This might be due to an invalid\n"
        "text proto format generated from the YAML file, or a mismatch\n"
        "between the YAML structure and the AnomalyRules.proto definition.",
        file=sys.stderr,
    )
    print(f"  Stderr: {e.stderr.decode('utf-8').strip()}", file=sys.stderr)
    sys.exit(1)
  finally:
    if temp_dir and os.path.exists(temp_dir):
      shutil.rmtree(temp_dir)
