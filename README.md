# Mineify - Server-Wide Music Player for Minecraft

A Minecraft Fabric mod that enables server-wide music playback. Players can search YouTube, add songs to a shared playlist, and listen together in real time all inside Minecraft.

## Usage

1. Join a server running Mineify
2. Press **N+M** to open the GUI
3. Type a song name in the search bar and press Enter
4. Click a result to add it to the playlist
5. The song downloads and plays automatically for all connected players

## How It Works

Everything runs inside the Minecraft server process — no companion service or extra open ports required.

```
┌─────────────────────────────────────────────────────────────────────┐
│                         MINECRAFT SERVER                            │
│  ┌─────────────────┐    ┌──────────────────┐                        │
│  │  Mineify Mod    │◄──►│ Playlist Manager │                        │
│  │  (Server-side)  │    │ (Track Queue)    │                        │
│  └────────┬────────┘    └──────────────────┘                        │
│           │                                                         │
│           │  ┌─────────────────┐  ┌──────────────────────────────┐  │
│           ├─►│ YouTubeService  │  │   AudioDownloadService       │  │
│           │  │ (InnerTube API) │  │   yt-dlp subprocess          │  │
│           │  └─────────────────┘  └──────────────────────────────┘  │
│           │                                                         │
│           │  Minecraft packets (chunked audio data)                 │
│           ▼                                                         │
│  ┌─────────────────┐    ┌──────────────────┐                        │
│  │  Mineify Mod    │───►│  AudioPlayer     │                        │
│  │  (Client-side)  │    │  (javax.sound)   │                        │
│  └─────────────────┘    └──────────────────┘                        │
└─────────────────────────────────────────────────────────────────────┘
```

1. **YouTubeService** calls YouTube's internal InnerTube API directly from Java — no API key needed.
2. **AudioDownloadService** invokes `yt-dlp` as a subprocess to download and convert audio to WAV.
3. The server splits the WAV file into chunks and delivers them to every client over the existing Minecraft connection — no extra port needs to be open.
4. Clients reassemble the chunks in memory and play the audio locally using `javax.sound.sampled`.

## Features

- **N+M keybind** — open the Mineify GUI from anywhere in-game
- **YouTube search** — search for songs directly inside Minecraft
- **Shared playlist** — all players see and contribute to the same queue
- **Automatic playback** — songs play automatically when added and advance through the queue
- **Late-join sync** — players who join mid-song automatically seek to the correct position
- **Stop on remove** — removing the current song stops playback for everyone immediately
- **Session cache** — downloaded files are reused for the duration of the server session
- **No extra ports** — audio is delivered over the Minecraft connection; no firewall changes needed
- **Self-contained** — everything is inside the single mod JAR, no companion service required

## Requirements

| | Requirement |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.19.5+ |
| Fabric API | Latest for 26.3 |
| Java | 25+ |
| Server only | [yt-dlp](https://github.com/yt-dlp/yt-dlp) on system PATH |
| Server only | [ffmpeg](https://ffmpeg.org/) on system PATH |

Clients only need the mod JAR — no extra tools required.

## Installation

### Server

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.3.
2. Install `yt-dlp` and `ffmpeg` on the server machine (see guide below).
3. Place [Fabric API](https://modrinth.com/mod/fabric-api) and `mineify-1.4.0.jar` in the server's `mods/` folder.
4. Start the server. Mineify initialises automatically.

**Optional** — create `config/mineify.json` to customise behaviour:
```json
{
  "downloadDir": "./mineify-downloads",
  "maxPlaylistSize": 50
}
```

### Client

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.3.
2. Place [Fabric API](https://modrinth.com/mod/fabric-api) and `mineify-1.4.0.jar` in `.minecraft/mods/`.
3. Launch Minecraft with the Fabric profile. No further setup needed.

## Installing yt-dlp and ffmpeg

Both tools are required on the **server only**.

### Linux (Debian/Ubuntu)
```bash
sudo apt update && sudo apt install ffmpeg
sudo curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp
sudo chmod a+rx /usr/local/bin/yt-dlp
```

### Linux (Fedora/RHEL)
```bash
sudo dnf install ffmpeg
sudo curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp
sudo chmod a+rx /usr/local/bin/yt-dlp
```

### macOS
```bash
brew install ffmpeg yt-dlp
```

### Windows (choose one package manager)
```
scoop install yt-dlp ffmpeg
```
```
choco install yt-dlp ffmpeg
```
```
winget install yt-dlp ffmpeg
```

#### Windows — manual install

**yt-dlp:**
1. Download `yt-dlp.exe` from the [releases page](https://github.com/yt-dlp/yt-dlp/releases/latest)
2. Place it in a folder such as `C:\yt-dlp`
3. Add that folder to your system PATH

**ffmpeg:**
1. Download from [gyan.dev](https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip)
2. Extract to a folder such as `C:\ffmpeg`
3. Add `C:\ffmpeg\bin` to your system PATH

**Adding to PATH (Windows):**
1. Press `Win + R`, type `sysdm.cpl`, press Enter
2. Go to **Advanced** → **Environment Variables**
3. Under **System variables**, select `Path` → **Edit** → **New**
4. Add the folder path and click **OK** on all dialogs
5. Restart your terminal/server

#### Verify installation
```bash
yt-dlp --version
ffmpeg -version
```
Both commands should print version information without errors.

## Project Structure

```
mine-ify/
├── fabric-mod/
│   └── src/
│       ├── main/java/com/mineify/
│       │   ├── Mineify.java                  # Server entry point & lifecycle
│       │   ├── MineifyConfig.java            # Config loader (mineify.json)
│       │   ├── server/
│       │   │   ├── PlaylistManager.java      # Playlist state & audio dispatch
│       │   │   ├── YouTubeService.java       # YouTube search via InnerTube API
│       │   │   └── AudioDownloadService.java # yt-dlp download wrapper
│       │   └── network/
│       │       ├── MineifyPackets.java       # Packet registration
│       │       └── packets/
│       │           ├── SearchRequestPacket.java
│       │           ├── AddToPlaylistPacket.java
│       │           ├── SearchResultsPacket.java
│       │           ├── PlaylistSyncPacket.java
│       │           ├── AudioChunkPacket.java  # Chunked audio delivery
│       │           └── NowPlayingPacket.java
│       └── client/java/com/mineify/
│           ├── MineifyClient.java            # Client entry point & packet handlers
│           └── client/
│               ├── MineifyScreen.java        # In-game GUI
│               ├── MineifyKeybinds.java      # N keybind registration
│               └── audio/
│                   └── AudioPlayer.java      # Chunk buffering & playback
└── README.md
```

## Development

Requires Java 21.

```bash
cd fabric-mod
./gradlew build          # Build the mod JAR
./gradlew runClient      # Launch a test Minecraft client
./gradlew runServer      # Launch a headless test server
```

## Legal

This mod is intended for personal and educational use. Users are responsible for complying with YouTube's Terms of Service and applicable copyright laws.

## License

MIT License — see `LICENSE` for details.
