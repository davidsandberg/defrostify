# Defrostify — TODO List

## Core Functionality & Verification
- [x] **Verify that the timer/schedule works**
- [x] **Verify that it works both locally (Wi-Fi) and remotely (Telldus Live Cloud)**
- [ ] **Test on another phone**

## UI & User Experience
- [x] **Polish the authentication dialog**
- [x] **Auto-update switch state if changed externally** (e.g. via the physical switch button or another app)
- [ ] **Remove the preconfigured start times (06:30, 07:00, ...) to save space**
- [ ] **Align text sizes across different panels on the main screen**
- [ ] **Check if the switch is online** (show 3 states on the main screen: `ON`, `OFF`, `OFFLINE`)

## Smart Features & Multi-Device Sync
- [ ] **Sync scheduled start time if set from another phone**
- [ ] **Use the power meter (wattage sensor) to verify that the heater is actually running**
- [ ] **Temperature-dependent start time** (adjust pre-heat duration based on outdoor temperature)
- [ ] **Shared heating history across multiple phones**

## Release & Distribution
- [x] **Upload to GitHub** (`davidsandberg/defrostify`)
- [ ] **Publish to Google Play**
