package com.mineify.client;

import com.mineify.Mineify;
import com.mineify.MineifyClient;
import com.mineify.network.packets.SearchRequestPacket;
import com.mineify.network.packets.LoadMoreSearchPacket;
import com.mineify.network.packets.AddToPlaylistPacket;
import com.mineify.network.packets.PlayTrackPacket;
import com.mineify.network.packets.RemoveFromHistoryPacket;
import com.mineify.network.packets.RemoveFromPlaylistPacket;
import com.mineify.network.packets.SkipTrackPacket;
import com.mineify.network.packets.StopTrackPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mineify.client.audio.AudioPlayer;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class MineifyScreen extends Screen {
    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 245;

    private EditBox searchField;
    private Button searchButton;

    private List<SearchResult> searchResults = new ArrayList<>();
    private List<PlaylistEntry> playlist = new ArrayList<>();
    private List<PlaylistEntry> history = new ArrayList<>();

    private int currentTab = 0;
    private int searchScrollOffset = 0;
    private boolean hasMoreSearchResults = false;
    private int playlistScrollOffset = 0;
    private int historyScrollOffset = 0;
    private boolean draggingScrollbar = false;

    private String nowPlaying = null;
    private String nowPlayingVideoId = null;
    private String nowPlayingThumbnail = null;
    private float playbackProgress = 0f;
    private int loadingSecondsRemaining = 0;

    private String currentPlayerName;
    private boolean isOp = false;
    private boolean canRemoveHistory = false;
    private String serverVersion = null;

    public MineifyScreen() {
        super(Component.literal("Mineify - Music Player"));
    }

    @Override
    protected void init() {
        super.init();

        this.currentPlayerName = Minecraft.getInstance().getUser().getName();
        // Load cached state (including isOp) before creating widgets below, so
        // op-gated ones (e.g. Stop Playback) know the right value immediately
        // instead of only after the first server sync arrives.
        requestPlaylistSync();

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = centerY - PANEL_HEIGHT / 2;

        this.searchField = new EditBox(
                this.font,
                panelLeft + 10,
                panelTop + 30,
                PANEL_WIDTH - 80,
                20,
                Component.literal("Search YouTube...")
        );
        this.searchField.setMaxLength(100);
        this.searchField.setHint(Component.literal("Search for songs..."));
        this.addRenderableWidget(this.searchField);

        this.searchButton = Button.builder(Component.literal("Search"), button -> {
            performSearch();
        }).bounds(panelLeft + PANEL_WIDTH - 60, panelTop + 30, 50, 20).build();
        this.addRenderableWidget(this.searchButton);

        Button searchTabBtn = Button.builder(Component.literal("Search"), button -> {
            this.currentTab = 0;
        }).bounds(panelLeft + 10, panelTop + 5, 60, 20).build();
        this.addRenderableWidget(searchTabBtn);

        Button playlistTabBtn = Button.builder(Component.literal("Playlist"), button -> {
            this.currentTab = 1;
        }).bounds(panelLeft + 75, panelTop + 5, 60, 20).build();
        this.addRenderableWidget(playlistTabBtn);

        Button recentTabBtn = Button.builder(Component.literal("Recent"), button -> {
            this.currentTab = 2;
        }).bounds(panelLeft + 140, panelTop + 5, 60, 20).build();
        this.addRenderableWidget(recentTabBtn);

        // Volume slider (below the panel)
        this.addRenderableWidget(new AbstractSliderButton(
                panelLeft + 10,
                panelTop + PANEL_HEIGHT + 5,
                PANEL_WIDTH - 20 - 60,
                20,
                Component.literal("Volume: " + (int)(AudioPlayer.getInstance().getVolume() * 100) + "%"),
                AudioPlayer.getInstance().getVolume()
        ) {
            @Override
            protected void updateMessage() {
                this.setMessage(Component.literal("Volume: " + (int)(this.value * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                AudioPlayer.getInstance().setVolume((float) this.value);
            }
        });

        // Skip button next to the volume slider
        this.addRenderableWidget(Button.builder(Component.literal("Skip >>"), button -> {
            ClientPlayNetworking.send(new SkipTrackPacket());
        }).bounds(panelLeft + 10 + (PANEL_WIDTH - 20 - 60) + 5, panelTop + PANEL_HEIGHT + 5, 55, 20).build());

        // Stop button on its own row below - halts playback entirely, so only
        // shown to ops (the server also enforces this independently either way)
        if (this.isOp) {
            this.addRenderableWidget(Button.builder(Component.literal("Stop Playback"), button -> {
                ClientPlayNetworking.send(new StopTrackPacket());
            }).bounds(panelLeft + 10, panelTop + PANEL_HEIGHT + 30, PANEL_WIDTH - 20, 20).build());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = centerY - PANEL_HEIGHT / 2;

        // Panel background - matching Minecraft widget style
        graphics.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + PANEL_HEIGHT, 0xE0101010);
        // Draw border using horizontal/vertical lines
        graphics.horizontalLine(panelLeft, panelLeft + PANEL_WIDTH - 1, panelTop, 0xFFAAAAAA);
        graphics.horizontalLine(panelLeft, panelLeft + PANEL_WIDTH - 1, panelTop + PANEL_HEIGHT - 1, 0xFF555555);
        graphics.verticalLine(panelLeft, panelTop, panelTop + PANEL_HEIGHT - 1, 0xFFAAAAAA);
        graphics.verticalLine(panelLeft + PANEL_WIDTH - 1, panelTop, panelTop + PANEL_HEIGHT - 1, 0xFF555555);

        graphics.centeredText(this.font, this.title, centerX, panelTop - 15, 0xFFFFFFFF);

        // Underline the active tab. Drawn fresh every frame straight from
        // currentTab, rather than updated reactively wherever currentTab gets
        // changed (e.g. adding a track from Recent also auto-switches to the
        // Playlist tab) - that way it can't go stale no matter what changed it.
        int[] tabX = { panelLeft + 10, panelLeft + 75, panelLeft + 140 };
        graphics.fill(tabX[currentTab], panelTop + 25, tabX[currentTab] + 60, panelTop + 27, 0xFF55FF55);

        // Render widgets after our background
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        if (currentTab == 0) {
            renderSearchTab(graphics, panelLeft, panelTop, mouseX, mouseY);
        } else if (currentTab == 1) {
            renderPlaylistTab(graphics, panelLeft, panelTop, mouseX, mouseY);
        } else {
            renderHistoryTab(graphics, panelLeft, panelTop, mouseX, mouseY);
        }

        renderNowPlaying(graphics, panelLeft, panelTop + PANEL_HEIGHT - 30);

        renderVersionIndicator(graphics);
    }

    /**
     * Quiet status line in the corner of the whole screen (not the panel) -
     * only appears when this client jar doesn't match the server, so it
     * stays silent (rather than a permanent green "up to date" line) for
     * the common case. Drawn at a reduced scale via the pose matrix since
     * the built-in font has no smaller variant.
     */
    private void renderVersionIndicator(GuiGraphicsExtractor graphics) {
        if (serverVersion == null || serverVersion.equals(Mineify.VERSION)) {
            return;
        }

        String versionText = "v" + Mineify.VERSION + " (out of date)";
        int versionColor = 0xFFFF5555;

        float scale = 0.7f;
        int versionWidth = this.font.width(versionText);
        float screenRight = this.width - 5;
        float screenBottom = this.height - 8;

        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);
        graphics.text(this.font, Component.literal(versionText),
                (int) ((screenRight - versionWidth * scale) / scale),
                (int) (screenBottom / scale),
                versionColor);
        graphics.pose().popMatrix();
    }

    // Matches the "- 5" used by every scroll-clamp formula below - the
    // approximate number of rows visible at once in a list panel.
    private static final int VISIBLE_ROWS = 5;
    private static final int THUMBNAIL_SIZE = 20;

    /**
     * Draws a video's thumbnail if it's already loaded, or a dim placeholder
     * square in its place while ThumbnailManager fetches it.
     */
    private void renderThumbnail(GuiGraphicsExtractor graphics, String videoId, int x, int y) {
        Identifier texture = ThumbnailManager.getInstance().get(videoId);
        if (texture != null) {
            graphics.blit(texture, x, y, x + THUMBNAIL_SIZE, y + THUMBNAIL_SIZE, 0f, 1f, 0f, 1f);
        } else {
            graphics.fill(x, y, x + THUMBNAIL_SIZE, y + THUMBNAIL_SIZE, 0x33FFFFFF);
        }
    }

    /**
     * Thin track-and-thumb scrollbar drawn in the panel's right margin
     * (rows only fill up to PANEL_WIDTH - 10, leaving room before the
     * border at PANEL_WIDTH). Only shown once there's actually more to
     * scroll than fits on screen.
     */
    private void renderScrollbar(GuiGraphicsExtractor graphics, int panelLeft, int listTop, int listHeight, int totalItems, int scrollOffset) {
        if (totalItems <= VISIBLE_ROWS) {
            return;
        }

        int trackX = panelLeft + PANEL_WIDTH - 8;
        int maxScroll = totalItems - VISIBLE_ROWS;

        graphics.fill(trackX, listTop, trackX + 3, listTop + listHeight, 0x33FFFFFF);

        int thumbHeight = Math.max(10, listHeight * VISIBLE_ROWS / totalItems);
        int thumbY = listTop + (listHeight - thumbHeight) * Math.min(scrollOffset, maxScroll) / maxScroll;

        graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, 0xAAFFFFFF);
    }

    // Shared by mouseScrolled and the scrollbar drag/click handling below,
    // so both ways of scrolling a list agree on the same bounds.
    private int getScrollTotal(int tab) {
        if (tab == 0) return searchResults.size() + (hasMoreSearchResults ? 1 : 0);
        if (tab == 1) return playlist.size();
        return history.size();
    }

    private int getScrollMax(int tab) {
        return Math.max(0, getScrollTotal(tab) - VISIBLE_ROWS);
    }

    private int getScrollOffset(int tab) {
        if (tab == 0) return searchScrollOffset;
        if (tab == 1) return playlistScrollOffset;
        return historyScrollOffset;
    }

    private void setScrollOffset(int tab, int value) {
        int clamped = Math.max(0, Math.min(value, getScrollMax(tab)));
        if (tab == 0) searchScrollOffset = clamped;
        else if (tab == 1) playlistScrollOffset = clamped;
        else historyScrollOffset = clamped;
    }

    /**
     * Maps a mouse Y position onto a scroll offset for the current tab's
     * list, as if the scrollbar thumb's center were being placed under the
     * cursor. Used both for an initial click-to-jump on the track and for
     * every subsequent mouseDragged update while dragging the thumb.
     */
    private void scrollToMouseY(double mouseY) {
        int listTop = this.height / 2 - PANEL_HEIGHT / 2 + 55;
        int listHeight = PANEL_HEIGHT - 90;

        int total = getScrollTotal(currentTab);
        if (total <= VISIBLE_ROWS) {
            return;
        }

        int thumbHeight = Math.max(10, listHeight * VISIBLE_ROWS / total);
        int usableTrack = listHeight - thumbHeight;
        double fraction = usableTrack > 0 ? (mouseY - listTop - thumbHeight / 2.0) / usableTrack : 0;
        fraction = Math.max(0, Math.min(1, fraction));

        setScrollOffset(currentTab, (int) Math.round(fraction * getScrollMax(currentTab)));
    }

    private void renderSearchTab(GuiGraphicsExtractor graphics, int panelLeft, int panelTop, int mouseX, int mouseY) {
        int listTop = panelTop + 55;
        int listHeight = PANEL_HEIGHT - 90;
        int itemHeight = 25;

        if (searchResults.isEmpty()) {
            graphics.centeredText(this.font, Component.literal("Search for songs above"),
                    panelLeft + PANEL_WIDTH / 2, listTop + 20, 0xFF888888);
        } else {
            int y = listTop;
            int i = searchScrollOffset;
            for (; i < searchResults.size() && y < listTop + listHeight - itemHeight; i++) {
                SearchResult result = searchResults.get(i);
                boolean hovered = mouseX >= panelLeft + 10 && mouseX <= panelLeft + PANEL_WIDTH - 10
                        && mouseY >= y && mouseY < y + itemHeight;

                int bgColor = hovered ? 0x44FFFFFF : 0x22FFFFFF;
                graphics.fill(panelLeft + 10, y, panelLeft + PANEL_WIDTH - 10, y + itemHeight - 2, bgColor);

                renderThumbnail(graphics, result.videoId, panelLeft + 12, y + 1);

                String title = truncateText(result.title, PANEL_WIDTH - 102);
                graphics.text(this.font, Component.literal(title), panelLeft + 37, y + 4, 0xFFFFFFFF);
                graphics.text(this.font, Component.literal(result.duration), panelLeft + PANEL_WIDTH - 50, y + 4, 0xFFAAAAAA);

                String channel = truncateText(result.channel, PANEL_WIDTH - 62);
                graphics.text(this.font, Component.literal(channel), panelLeft + 37, y + 14, 0xFF888888);

                y += itemHeight;
            }

            // "Load more" is one extra row right after the last loaded result -
            // only shown once scrolled to the true end of what's loaded (i ran
            // out of results, not just vertical space), same click-a-row pattern
            // as everything else in this list.
            if (hasMoreSearchResults && i >= searchResults.size() && y < listTop + listHeight - itemHeight) {
                boolean hovered = mouseX >= panelLeft + 10 && mouseX <= panelLeft + PANEL_WIDTH - 10
                        && mouseY >= y && mouseY < y + itemHeight;
                int bgColor = hovered ? 0x44FFFFFF : 0x22FFFFFF;
                graphics.fill(panelLeft + 10, y, panelLeft + PANEL_WIDTH - 10, y + itemHeight - 2, bgColor);
                graphics.centeredText(this.font, Component.literal("Load more..."),
                        panelLeft + PANEL_WIDTH / 2, y + 8, 0xFFAAAAAA);
            }

            renderScrollbar(graphics, panelLeft, listTop, listHeight,
                    searchResults.size() + (hasMoreSearchResults ? 1 : 0), searchScrollOffset);
        }
    }

    private void renderPlaylistTab(GuiGraphicsExtractor graphics, int panelLeft, int panelTop, int mouseX, int mouseY) {
        int listTop = panelTop + 55;
        int listHeight = PANEL_HEIGHT - 90;
        int itemHeight = 25;

        if (playlist.isEmpty()) {
            graphics.centeredText(this.font, Component.literal("Playlist is empty"),
                    panelLeft + PANEL_WIDTH / 2, listTop + 20, 0xFF888888);
            graphics.centeredText(this.font, Component.literal("Search and add songs!"),
                    panelLeft + PANEL_WIDTH / 2, listTop + 35, 0xFF666666);
        } else {
            int y = listTop;
            for (int i = playlistScrollOffset; i < playlist.size() && y < listTop + listHeight - itemHeight; i++) {
                PlaylistEntry entry = playlist.get(i);
                boolean hovered = mouseX >= panelLeft + 10 && mouseX <= panelLeft + PANEL_WIDTH - 10
                        && mouseY >= y && mouseY < y + itemHeight;
                boolean isPlaying = nowPlaying != null && entry.videoId.equals(nowPlayingVideoId);

                int bgColor = isPlaying ? 0x4400FF00 : (hovered ? 0x44FFFFFF : 0x22FFFFFF);
                graphics.fill(panelLeft + 10, y, panelLeft + PANEL_WIDTH - 10, y + itemHeight - 2, bgColor);

                renderThumbnail(graphics, entry.videoId, panelLeft + 12, y + 1);

                // Adjust title width for whichever buttons are present
                // Shown to everyone - the server enforces ownership (or op) before
                // actually removing, same as Skip/Stop, so this stays consistent
                // even for players who can't tell locally whether they're op.
                boolean canRemove = entry.addedBy.equals(currentPlayerName) || isOp;
                boolean canPlay = nowPlaying == null;
                int titleMaxWidth = PANEL_WIDTH - 102 - (canRemove ? 25 : 0) - (canPlay ? 20 : 0);
                String title = truncateText(entry.title, titleMaxWidth);
                graphics.text(this.font, Component.literal(title), panelLeft + 37, y + 4, 0xFFFFFFFF);

                String addedBy = "by " + entry.addedBy;
                graphics.text(this.font, Component.literal(addedBy), panelLeft + 37, y + 14, 0xFF888888);

                int buttonRight = panelLeft + PANEL_WIDTH - 25;

                // Render remove button for songs added by current player
                if (canRemove) {
                    int removeX = buttonRight;
                    int removeY = y + 5;
                    boolean removeHovered = mouseX >= removeX && mouseX <= removeX + 15
                            && mouseY >= removeY && mouseY <= removeY + 15;
                    int removeBgColor = removeHovered ? 0xAAFF4444 : 0x66FF4444;
                    graphics.fill(removeX, removeY, removeX + 15, removeY + 15, removeBgColor);
                    graphics.centeredText(this.font, Component.literal("X"),
                            removeX + 8, removeY + 4, 0xFFFFFFFF);
                    buttonRight -= 20;
                }

                // Render play button when nothing is currently playing
                if (canPlay) {
                    int playX = buttonRight;
                    int playY = y + 5;
                    boolean playHovered = mouseX >= playX && mouseX <= playX + 15
                            && mouseY >= playY && mouseY <= playY + 15;
                    int playBgColor = playHovered ? 0xAA44FF44 : 0x6644FF44;
                    graphics.fill(playX, playY, playX + 15, playY + 15, playBgColor);
                    graphics.centeredText(this.font, Component.literal(">"),
                            playX + 8, playY + 4, 0xFFFFFFFF);
                }

                y += itemHeight;
            }

            renderScrollbar(graphics, panelLeft, listTop, listHeight, playlist.size(), playlistScrollOffset);
        }
    }

    private void renderHistoryTab(GuiGraphicsExtractor graphics, int panelLeft, int panelTop, int mouseX, int mouseY) {
        int listTop = panelTop + 55;
        int listHeight = PANEL_HEIGHT - 90;
        int itemHeight = 25;

        if (history.isEmpty()) {
            graphics.centeredText(this.font, Component.literal("Nothing played yet"),
                    panelLeft + PANEL_WIDTH / 2, listTop + 20, 0xFF888888);
        } else {
            int y = listTop;
            for (int i = historyScrollOffset; i < history.size() && y < listTop + listHeight - itemHeight; i++) {
                PlaylistEntry entry = history.get(i);
                boolean hovered = mouseX >= panelLeft + 10 && mouseX <= panelLeft + PANEL_WIDTH - 10
                        && mouseY >= y && mouseY < y + itemHeight;

                int bgColor = hovered ? 0x44FFFFFF : 0x22FFFFFF;
                graphics.fill(panelLeft + 10, y, panelLeft + PANEL_WIDTH - 10, y + itemHeight - 2, bgColor);

                renderThumbnail(graphics, entry.videoId, panelLeft + 12, y + 1);

                String title = truncateText(entry.title, PANEL_WIDTH - 62 - (canRemoveHistory ? 20 : 0));
                graphics.text(this.font, Component.literal(title), panelLeft + 37, y + 4, 0xFFFFFFFF);

                String addedBy = "by " + entry.addedBy + "  " + entry.duration;
                graphics.text(this.font, Component.literal(addedBy), panelLeft + 37, y + 14, 0xFF888888);

                int buttonRight = panelLeft + PANEL_WIDTH - 25;

                if (canRemoveHistory) {
                    int removeX = buttonRight;
                    int removeY = y + 5;
                    boolean removeHovered = mouseX >= removeX && mouseX <= removeX + 15
                            && mouseY >= removeY && mouseY <= removeY + 15;
                    int removeBgColor = removeHovered ? 0xAAFF4444 : 0x66FF4444;
                    graphics.fill(removeX, removeY, removeX + 15, removeY + 15, removeBgColor);
                    graphics.centeredText(this.font, Component.literal("X"), removeX + 8, removeY + 4, 0xFFFFFFFF);
                    buttonRight -= 20;
                }

                int addX = buttonRight;
                int addY = y + 5;
                boolean addHovered = mouseX >= addX && mouseX <= addX + 15
                        && mouseY >= addY && mouseY <= addY + 15;
                int addBgColor = addHovered ? 0xAA44FF44 : 0x6644FF44;
                graphics.fill(addX, addY, addX + 15, addY + 15, addBgColor);
                graphics.centeredText(this.font, Component.literal("+"), addX + 8, addY + 4, 0xFFFFFFFF);

                y += itemHeight;
            }

            renderScrollbar(graphics, panelLeft, listTop, listHeight, history.size(), historyScrollOffset);
        }
    }

    private void renderNowPlaying(GuiGraphicsExtractor graphics, int panelLeft, int y) {
        graphics.fill(panelLeft, y, panelLeft + PANEL_WIDTH, y + 25, 0x60000000);

        if (nowPlaying != null) {
            renderThumbnail(graphics, nowPlayingVideoId, panelLeft + 3, y + 2);

            // The server's progress/ETA is a broadcast estimate based on a fixed
            // theoretical transfer rate, and only updates once a second - actual
            // per-client completion can beat it, in which case we have better
            // information locally than the server does: if this client's own
            // AudioPlayer has already started this exact track, trust that over
            // a server estimate that hasn't caught up yet.
            boolean actuallyPlaying = AudioPlayer.getInstance().isPlaying()
                    && nowPlaying.equals(AudioPlayer.getInstance().getCurrentTitle());
            boolean loading = playbackProgress <= 0f && !actuallyPlaying;
            String suffix = loading && loadingSecondsRemaining > 0 ? " (~" + loadingSecondsRemaining + "s)" : "";
            String prefix = loading ? "⏳ Loading: " : "♪ ";
            String text = prefix + truncateText(nowPlaying, PANEL_WIDTH - 58 - (loading ? 50 : 0)) + suffix;
            graphics.text(this.font, Component.literal(text), panelLeft + 28, y + 4, loading ? 0xFFFFCC55 : 0xFF55FF55);

            int barWidth = PANEL_WIDTH - 38;
            int barX = panelLeft + 28;
            int barY = y + 18;
            graphics.fill(barX, barY, barX + barWidth, barY + 3, 0x44FFFFFF);
            graphics.fill(barX, barY, barX + (int)(barWidth * playbackProgress), barY + 3, 0xFF55FF55);
        } else {
            graphics.text(this.font, Component.literal("Nothing playing"), panelLeft + 10, y + 8, 0xFF666666);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == 0) {
            double mouseX = click.x();
            double mouseY = click.y();

            int centerX = this.width / 2;
            int centerY = this.height / 2;
            int panelLeft = centerX - PANEL_WIDTH / 2;
            int panelTop = centerY - PANEL_HEIGHT / 2;
            int listTop = panelTop + 55;
            int listHeight = PANEL_HEIGHT - 90;
            int itemHeight = 25;

            // Scrollbar thumb/track takes priority over row content - it
            // sits in the panel's right margin (past PANEL_WIDTH - 10, where
            // every row's clickable area ends) so there's no real overlap,
            // but checking it first keeps that boundary unambiguous.
            int trackX = panelLeft + PANEL_WIDTH - 8;
            if (mouseX >= trackX - 3 && mouseX <= trackX + 6
                    && mouseY >= listTop && mouseY <= listTop + listHeight
                    && getScrollTotal(currentTab) > VISIBLE_ROWS) {
                draggingScrollbar = true;
                scrollToMouseY(mouseY);
                return true;
            }

            if (currentTab == 0 && !searchResults.isEmpty()) {
                int y = listTop;
                int i = searchScrollOffset;
                for (; i < searchResults.size(); i++) {
                    if (mouseX >= panelLeft + 10 && mouseX <= panelLeft + PANEL_WIDTH - 10
                            && mouseY >= y && mouseY < y + itemHeight) {
                        addToPlaylist(searchResults.get(i));
                        return true;
                    }
                    y += itemHeight;
                }
                if (hasMoreSearchResults
                        && mouseX >= panelLeft + 10 && mouseX <= panelLeft + PANEL_WIDTH - 10
                        && mouseY >= y && mouseY < y + itemHeight) {
                    loadMoreSearchResults();
                    return true;
                }
            }

            // Handle remove/play button clicks in playlist tab
            if (currentTab == 1 && !playlist.isEmpty()) {
                int y = listTop;
                for (int i = playlistScrollOffset; i < playlist.size(); i++) {
                    PlaylistEntry entry = playlist.get(i);
                    // Shown to everyone - the server enforces ownership (or op) before
                // actually removing, same as Skip/Stop, so this stays consistent
                // even for players who can't tell locally whether they're op.
                boolean canRemove = entry.addedBy.equals(currentPlayerName) || isOp;
                    boolean canPlay = nowPlaying == null;
                    int buttonRight = panelLeft + PANEL_WIDTH - 25;

                    if (canRemove) {
                        int removeX = buttonRight;
                        int removeY = y + 5;
                        if (mouseX >= removeX && mouseX <= removeX + 15
                                && mouseY >= removeY && mouseY <= removeY + 15) {
                            removeFromPlaylist(entry.videoId);
                            return true;
                        }
                        buttonRight -= 20;
                    }

                    if (canPlay) {
                        int playX = buttonRight;
                        int playY = y + 5;
                        if (mouseX >= playX && mouseX <= playX + 15
                                && mouseY >= playY && mouseY <= playY + 15) {
                            playSpecificTrack(entry.videoId);
                            return true;
                        }
                    }

                    y += itemHeight;
                }
            }

            // Handle add/remove button clicks in the recent tab
            if (currentTab == 2 && !history.isEmpty()) {
                int y = listTop;
                for (int i = historyScrollOffset; i < history.size(); i++) {
                    PlaylistEntry entry = history.get(i);
                    int buttonRight = panelLeft + PANEL_WIDTH - 25;

                    if (canRemoveHistory) {
                        int removeX = buttonRight;
                        int removeY = y + 5;
                        if (mouseX >= removeX && mouseX <= removeX + 15
                                && mouseY >= removeY && mouseY <= removeY + 15) {
                            removeFromHistory(entry.videoId);
                            return true;
                        }
                        buttonRight -= 20;
                    }

                    int addX = buttonRight;
                    int addY = y + 5;
                    if (mouseX >= addX && mouseX <= addX + 15
                            && mouseY >= addY && mouseY <= addY + 15) {
                        addToPlaylist(entry.videoId, entry.title, entry.duration, entry.thumbnail);
                        return true;
                    }
                    y += itemHeight;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        setScrollOffset(currentTab, getScrollOffset(currentTab) - (int) verticalAmount);
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingScrollbar) {
            scrollToMouseY(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.key() == InputConstants.KEY_RETURN && this.searchField.isFocused()) {
            performSearch();
            return true;
        }
        return super.keyPressed(input);
    }

    private void performSearch() {
        String query = this.searchField.getValue().trim();
        if (query.isEmpty()) return;

        MineifyClient.LOGGER.info("Searching for: {}", query);
        ClientPlayNetworking.send(new SearchRequestPacket(query));
        this.searchResults.clear();
        this.searchScrollOffset = 0;
        // Switch to search tab to show results
        this.currentTab = 0;
    }

    private void addToPlaylist(SearchResult result) {
        addToPlaylist(result.videoId, result.title, result.duration, result.thumbnail);
    }

    private void addToPlaylist(String videoId, String title, String duration, String thumbnail) {
        MineifyClient.LOGGER.info("Adding to playlist: {}", title);
        ClientPlayNetworking.send(new AddToPlaylistPacket(videoId, title, duration, thumbnail));
        // Switch to playlist tab to prevent accidental double-clicks
        this.currentTab = 1;
    }

    private void removeFromPlaylist(String videoId) {
        MineifyClient.LOGGER.info("Removing from playlist: {}", videoId);
        ClientPlayNetworking.send(new RemoveFromPlaylistPacket(videoId));
    }

    private void removeFromHistory(String videoId) {
        MineifyClient.LOGGER.info("Removing from history: {}", videoId);
        ClientPlayNetworking.send(new RemoveFromHistoryPacket(videoId));
    }

    private void playSpecificTrack(String videoId) {
        MineifyClient.LOGGER.info("Requesting playback of: {}", videoId);
        ClientPlayNetworking.send(new PlayTrackPacket(videoId));
    }

    private void requestPlaylistSync() {
        // Load cached state from MineifyClient
        this.playlist = MineifyClient.getCachedPlaylist();
        this.history = MineifyClient.getCachedHistory();
        this.nowPlaying = MineifyClient.getCachedNowPlaying();
        this.nowPlayingVideoId = MineifyClient.getCachedNowPlayingVideoId();
        this.nowPlayingThumbnail = MineifyClient.getCachedNowPlayingThumbnail();
        this.playbackProgress = MineifyClient.getCachedProgress();
        this.loadingSecondsRemaining = MineifyClient.getCachedLoadingSecondsRemaining();
        this.isOp = MineifyClient.getCachedIsOp();
        this.canRemoveHistory = MineifyClient.getCachedCanRemoveHistory();
        this.serverVersion = MineifyClient.getCachedServerVersion();
    }

    public void updateSearchResults(List<SearchResult> results, boolean append, boolean hasMore) {
        if (append) {
            this.searchResults.addAll(results);
        } else {
            this.searchResults = new ArrayList<>(results);
            this.searchScrollOffset = 0;
        }
        this.hasMoreSearchResults = hasMore;
    }

    private void loadMoreSearchResults() {
        ClientPlayNetworking.send(new LoadMoreSearchPacket());
    }

    public void updatePlaylist(List<PlaylistEntry> entries, boolean isOp, String serverVersion) {
        this.playlist = entries;
        this.isOp = isOp;
        this.serverVersion = serverVersion;
    }

    public void updateHistory(List<PlaylistEntry> entries, boolean canRemove) {
        this.history = entries;
        this.canRemoveHistory = canRemove;
    }

    public void updateNowPlaying(String videoId, String title, float progress, int loadingSecondsRemaining, String thumbnail) {
        this.nowPlaying = title.isEmpty() ? null : title;
        this.nowPlayingVideoId = title.isEmpty() ? null : videoId;
        this.nowPlayingThumbnail = title.isEmpty() ? null : thumbnail;
        this.playbackProgress = progress;
        this.loadingSecondsRemaining = loadingSecondsRemaining;
    }

    private String truncateText(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) return text;
        while (this.font.width(text + "...") > maxWidth && text.length() > 0) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "...";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public static class SearchResult {
        public final String videoId;
        public final String title;
        public final String channel;
        public final String duration;
        public final String thumbnail;

        public SearchResult(String videoId, String title, String channel, String duration, String thumbnail) {
            this.videoId = videoId;
            this.title = title;
            this.channel = channel;
            this.duration = duration;
            this.thumbnail = thumbnail;
        }
    }

    public static class PlaylistEntry {
        public final String videoId;
        public final String title;
        public final String duration;
        public final String addedBy;
        public final String thumbnail;

        public PlaylistEntry(String videoId, String title, String duration, String addedBy, String thumbnail) {
            this.videoId = videoId;
            this.title = title;
            this.duration = duration;
            this.addedBy = addedBy;
            this.thumbnail = thumbnail;
        }
    }
}
