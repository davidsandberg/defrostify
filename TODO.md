# Defrostify — TODO List

## Core Functionality & Verification
- [x] **Verify that the timer/schedule works** *(Kolla att timern funkar också)*
- [ ] **Verify that it works both locally (Wi-Fi) and remotely (Telldus Live Cloud)** *(Kolla att den funkar både local och remote)*
- [ ] **Test on another phone** *(Testa på en annan telefon)*

## UI & User Experience
- [x] **Polish the authentication dialog** *(Snygga till autentiseringsdialogen)*
- [x] **Auto-update switch state if changed externally** (e.g. via the physical switch button or another app) *(Uppdatera state på switchen om den t.ex ändras med knappen)*

## Smart Features & Multi-Device Sync
- [ ] **Sync scheduled start time if set from another phone** *(Uppdatera starttid om den sätts från en annan telefon)*
- [ ] **Use the power meter (wattage sensor) to verify that the heater is actually running** *(Använd effektmätaren för att se att värmaren kör)*
- [ ] **Temperature-dependent start time** (adjust pre-heat duration based on outdoor temperature) *(Temperaturberoende tid för start)*
- [ ] **Shared heating history across multiple phones** *(Historik — gemensam historik för flera telefoner)*

## Release & Distribution
- [x] **Upload to GitHub** *(Ladda upp på github — `davidsandberg/defrostify`)*
- [ ] **Publish to Google Play** *(Ladda upp på google play)*
