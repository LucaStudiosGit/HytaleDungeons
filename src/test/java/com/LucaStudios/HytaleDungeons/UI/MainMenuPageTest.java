package com.LucaStudios.HytaleDungeons.UI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MainMenuPageTest {

    private static final class FakePage implements MainMenuPage.PageController {
        int closeCalls = 0;

        @Override
        public void close() {
            closeCalls++;
        }
    }

    private static final class FakeActions implements MainMenuPage.PlayerActions {
        int chatCalls = 0;
        int disconnectCalls = 0;
        String lastPrefix;
        String lastUrl;
        String lastReason;

        @Override
        public void sendDiscordLink(String prefix, String url) {
            chatCalls++;
            lastPrefix = prefix;
            lastUrl = url;
        }

        @Override
        public void disconnect(String reason) {
            disconnectCalls++;
            lastReason = reason;
        }
    }

    @Test
    void handleStartClosesPage() {
        FakePage page = new FakePage();
        MainMenuPage.handleStart(page);
        assertEquals(1, page.closeCalls, "Start should close the page exactly once");
    }

    @Test
    void handleStartWithNullPageIsNoOp() {
        assertDoesNotThrow(() -> MainMenuPage.handleStart(null));
    }

    @Test
    void handleDiscordSendsChatLinkWithExpectedUrl() {
        FakeActions actions = new FakeActions();
        MainMenuPage.handleDiscord(actions);
        assertEquals(1, actions.chatCalls);
        assertEquals(MainMenuPage.DISCORD_CHAT_PREFIX, actions.lastPrefix);
        assertEquals(MainMenuPage.DISCORD_URL, actions.lastUrl);
    }

    @Test
    void handleDiscordDoesNotDisconnect() {
        FakeActions actions = new FakeActions();
        MainMenuPage.handleDiscord(actions);
        assertEquals(0, actions.disconnectCalls, "Discord must not disconnect the player");
    }

    @Test
    void handleQuitDisconnectsWithExpectedReason() {
        FakeActions actions = new FakeActions();
        MainMenuPage.handleQuit(actions);
        assertEquals(1, actions.disconnectCalls);
        assertEquals(MainMenuPage.DISCONNECT_REASON, actions.lastReason);
        assertEquals(0, actions.chatCalls, "Quit must not send chat");
    }

    @Test
    void discordUrlConstantIsPinned() {
        assertEquals("https://discord.gg/WTdNGetHsP", MainMenuPage.DISCORD_URL);
    }

    @Test
    void buttonIdsAreDistinctAndNonEmpty() {
        assertNotNull(MainMenuPage.BTN_START);
        assertNotNull(MainMenuPage.BTN_DISCORD);
        assertNotNull(MainMenuPage.BTN_QUIT);
        assertFalse(MainMenuPage.BTN_START.isEmpty());
        assertFalse(MainMenuPage.BTN_DISCORD.isEmpty());
        assertFalse(MainMenuPage.BTN_QUIT.isEmpty());
        assertNotEquals(MainMenuPage.BTN_START, MainMenuPage.BTN_DISCORD);
        assertNotEquals(MainMenuPage.BTN_START, MainMenuPage.BTN_QUIT);
        assertNotEquals(MainMenuPage.BTN_DISCORD, MainMenuPage.BTN_QUIT);
    }
}
