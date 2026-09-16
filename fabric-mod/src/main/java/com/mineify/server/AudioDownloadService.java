package com.mineify.server;

import com.mineify.Mineify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Downloads audio from YouTube using yt-dlp as a subprocess and stores the
 * resulting WAV files on disk. The server then reads and delivers the file
 * contents to clients via Minecraft packets (no separate HTTP port needed).
 */
public class AudioDownloadService {
    private final Path downloadDir;
    private final ExecutorService downloadExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Mineify-Downloader");
        t.setDaemon(true);
        return t;
    });
    private boolean ytDlpAvailable = false;
    private boolean ffmpegAvailable = false;

    public AudioDownloadService(Path downloadDir) throws IOException {
        this.downloadDir = downloadDir;
        Files.createDirectories(downloadDir);
        checkDependencies();
    }

    /**
     * Downloads a YouTube video as WAV using yt-dlp.
     * Returns a CompletableFuture with the Path to the WAV file, or null on failure.
     */
    public CompletableFuture<Path> download(String videoId) {
        return CompletableFuture.supplyAsync(() -> {
            Path filePath = downloadDir.resolve(videoId + ".wav");

            if (Files.exists(filePath)) {
                Mineify.LOGGER.info("Mineify: Using cached audio for {}", videoId);
                return filePath;
            }

            Mineify.LOGGER.info("Mineify: Downloading audio for {}", videoId);
            try {
                // Downmix to mono and downsample to 22.05 kHz: this mod streams the
                // resulting WAV to every listener as raw bytes over Minecraft's packet
                // protocol, so full 44.1 kHz stereo (tens of MB per song) saturates
                // player connections badly enough to cause keep-alive timeout kicks.
                // Cutting to mono/22050 shrinks that by ~4x with an acceptable quality
                // trade-off for in-game background music.
                // Also grab YouTube's lowest-bitrate audio-only stream rather than the
                // best available - we downsample this hard regardless, so a smaller
                // source doesn't cost any additional real quality, and it downloads
                // (and then converts) faster.
                // Points at a deno binary placed in the server's own working directory
                // rather than relying on PATH, since the main server runs as a
                // different OS user (pufferpanel) that wouldn't see a PATH change
                // made for this one. Lets yt-dlp use its standard extraction path
                // instead of the deprecated no-JS-runtime fallback.
                ProcessBuilder pb = new ProcessBuilder(
                        "yt-dlp",
                        "--js-runtimes", "deno:./deno",
                        "-f", "worstaudio",
                        "-x",
                        "--audio-format", "wav",
                        "--audio-quality", "0",
                        "--postprocessor-args", "ffmpeg:-ar 22050 -ac 1",
                        "-o", filePath.toString(),
                        "--no-playlist",
                        "https://www.youtube.com/watch?v=" + videoId
                );
                pb.redirectErrorStream(true);

                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes());
                boolean finished = process.waitFor(2, TimeUnit.MINUTES);

                if (!finished || process.exitValue() != 0) {
                    Mineify.LOGGER.error("yt-dlp failed for {}: {}", videoId, output);
                    if (output.contains("ffprobe and ffmpeg not found") || output.contains("ffmpeg not found")) {
                        Mineify.LOGGER.error("Mineify: ffmpeg is not installed! Please install ffmpeg to enable audio downloads.");
                        Mineify.LOGGER.error("Mineify: On Windows: winget install ffmpeg OR choco install ffmpeg OR scoop install ffmpeg");
                        Mineify.LOGGER.error("Mineify: On Linux: sudo apt install ffmpeg OR sudo dnf install ffmpeg");
                        Mineify.LOGGER.error("Mineify: On macOS: brew install ffmpeg");
                    }
                    return null;
                }

                if (!Files.exists(filePath)) {
                    Mineify.LOGGER.error("yt-dlp produced no output file for {}", videoId);
                    return null;
                }

                return filePath;
            } catch (IOException e) {
                Mineify.LOGGER.error("yt-dlp not found or failed to start for {}. Is yt-dlp installed?", videoId, e);
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Mineify.LOGGER.error("Download interrupted for {}", videoId, e);
                return null;
            }
        }, downloadExecutor);
    }

    public Path getFilePath(String videoId) {
        return downloadDir.resolve(videoId + ".wav");
    }

    public void delete(String videoId) {
        Path filePath = downloadDir.resolve(videoId + ".wav");
        try {
            Files.deleteIfExists(filePath);
            Mineify.LOGGER.info("Mineify: Deleted audio for {}", videoId);
        } catch (IOException e) {
            Mineify.LOGGER.warn("Mineify: Failed to delete audio for {}", videoId, e);
        }
    }

    private void checkDependencies() {
        // Check yt-dlp
        try {
            Process p = new ProcessBuilder("yt-dlp", "--version").start();
            String version = new String(p.getInputStream().readAllBytes()).trim();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                ytDlpAvailable = true;
                Mineify.LOGGER.info("Mineify: Found yt-dlp version {}", version);
            } else {
                Mineify.LOGGER.error("Mineify: yt-dlp check failed. Audio downloads will not work!");
            }
        } catch (IOException e) {
            Mineify.LOGGER.error("Mineify: yt-dlp not found! Please install yt-dlp and ensure it's on the system PATH.");
            Mineify.LOGGER.error("Mineify: See https://github.com/yt-dlp/yt-dlp#installation for installation instructions.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Check ffmpeg
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                ffmpegAvailable = true;
                Mineify.LOGGER.info("Mineify: Found ffmpeg");
            } else {
                Mineify.LOGGER.error("Mineify: ffmpeg check failed. Audio conversion will not work!");
            }
        } catch (IOException e) {
            Mineify.LOGGER.error("Mineify: ffmpeg not found! Please install ffmpeg and ensure it's on the system PATH.");
            Mineify.LOGGER.error("Mineify: ffmpeg is required by yt-dlp to convert audio to WAV format.");
            Mineify.LOGGER.error("Mineify: See https://ffmpeg.org/download.html for installation instructions.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!ytDlpAvailable || !ffmpegAvailable) {
            Mineify.LOGGER.warn("Mineify: ============================================================");
            Mineify.LOGGER.warn("Mineify: AUDIO DOWNLOADS WILL FAIL - Missing required dependencies!");
            Mineify.LOGGER.warn("Mineify: Please install both yt-dlp and ffmpeg on the server.");
            Mineify.LOGGER.warn("Mineify: ============================================================");
        }
    }

    public boolean isDependenciesAvailable() {
        return ytDlpAvailable && ffmpegAvailable;
    }

    public void shutdown() {
        downloadExecutor.shutdownNow();
        Mineify.LOGGER.info("Mineify: Audio download service shut down");
    }
}
