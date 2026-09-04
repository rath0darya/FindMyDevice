# Offline Relay Component

FindMyDevice now includes an opt-in BLE relay engine.

## Model

1. A FindMyDevice target can advertise an opaque 8-byte recovery tag over BLE.
2. A nearby FindMyDevice relay device can scan for the service UUID.
3. The relay records the observed tag together with its own last-known location, accuracy, RSSI and timestamp.
4. The relay stores the sighting locally until a future authorized synchronization transport is available.

The relay does not decrypt the target payload and does not provide a general-purpose tracker for arbitrary Bluetooth devices.

## Important limitation

A completely offline relay cannot deliver the sighting to a remote owner by itself. A transport path is still required eventually, such as the owner device coming into BLE range or a later Internet/SMS synchronization path.
