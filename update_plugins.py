import json
import os

plugins_file = 'plugins.json'
with open(plugins_file, 'r', encoding='utf-8') as f:
    plugins = json.load(f)

# Update or Add Footybite
footybite = {
    "iconUrl": "https://www.google.com/s2/favicons?domain=live.footybite.to&sz=256",
    "apiVersion": 1,
    "repositoryUrl": "https://github.com/hussienessam04/ArabicProviders",
    "fileSize": 18000,
    "status": 1,
    "language": "en",
    "authors": [
      "flavasava2022"
    ],
    "tvTypes": [
      "Live"
    ],
    "version": 2,
    "internalName": "Footybite",
    "url": "https://github.com/hussienessam04/ArabicProviders/raw/refs/heads/main/build/Footybite.cs3",
    "name": "Footybite"
}

# Update or Add StreamBroadcast
streambroadcast = {
    "iconUrl": "https://www.google.com/s2/favicons?domain=streambroadcast.net&sz=256",
    "apiVersion": 1,
    "repositoryUrl": "https://github.com/hussienessam04/ArabicProviders",
    "fileSize": 18000,
    "status": 1,
    "language": "en",
    "authors": [
      "flavasava2022"
    ],
    "tvTypes": [
      "Live"
    ],
    "version": 2,
    "internalName": "StreamBroadcast",
    "url": "https://github.com/hussienessam04/ArabicProviders/raw/refs/heads/main/build/StreamBroadcast.cs3",
    "name": "StreamBroadcast"
}

# Update or Add Streamed version 11
streamed = next((p for p in plugins if p["internalName"] == "Streamed"), None)
if streamed:
    streamed["version"] = 11

# Add Footybite if not exists
if not any(p["internalName"] == "Footybite" for p in plugins):
    plugins.append(footybite)
else:
    # Update version if exists
    for p in plugins:
        if p["internalName"] == "Footybite":
            p["version"] = 2

# Add StreamBroadcast if not exists
if not any(p["internalName"] == "StreamBroadcast" for p in plugins):
    plugins.append(streambroadcast)
else:
    # Update version if exists
    for p in plugins:
        if p["internalName"] == "StreamBroadcast":
            p["version"] = 2

with open(plugins_file, 'w', encoding='utf-8') as f:
    json.dump(plugins, f, indent=2, ensure_ascii=False)

print("Plugins updated successfully.")
