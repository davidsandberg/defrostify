# Defrostify — TODO List

## Core Functionality & Verification
- [x] **Verify that the timer/schedule works**
- [x] **Verify that it works both locally (Wi-Fi) and remotely (Telldus Live Cloud)**
- [ ] **Test when not connected locally** (e.g. over mobile data / away from home Wi-Fi)
- [ ] **Test on another phone**

## UI & User Experience
- [x] **Polish the authentication dialog**
- [x] **Auto-update switch state if changed externally** (e.g. via the physical switch button or another app)
- [x] **Remove the preconfigured start times (06:30, 07:00, ...) to save space**
- [x] **Align text sizes across different panels on the main screen**
- [x] **Show live power consumption (`W`) next to the `ON` / `OFF` switch status badge**

## Smart Features & Multi-Device Sync
- [ ] **Sync scheduled start time if set from another phone**
- [x] **Use the power meter (wattage sensor) to verify that the heater is actually running**
- [ ] **Temperature-dependent start time** (adjust pre-heat duration based on outdoor temperature)
- [ ] **Shared heating history across multiple phones**

## Release & Distribution
- [x] **Upload to GitHub** (`davidsandberg/defrostify`)
- [ ] **Publish to Google Play**
