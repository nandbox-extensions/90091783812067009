package com.nandbox.extension;

import com.nandbox.bots.api.Nandbox;
import com.nandbox.bots.api.NandboxClient;
import com.nandbox.bots.api.data.ExtensionDocResponse;
import com.nandbox.bots.api.inmessages.IncomingMessage;
import com.nandbox.bots.api.services.DatabaseService;
import com.nandbox.bots.api.util.Utils;
import com.nandbox.extension.ExtensionAdapter;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;

public class ExtensionCustomLogic extends ExtensionAdapter {

    private Nandbox.Api api;

    private static final String TABLE_MESSAGES = "echo_messages";
    private static final int MAX_RETURN_MESSAGES = 200;

    public static void main(String[] args) throws Exception {
        String TOKEN = "";
        Properties properties = new Properties();
        FileInputStream input = null;

        try {
            input = new FileInputStream("config.properties");
            properties.load(input);
            TOKEN = properties.getProperty("Token");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                }
            }
        }

        NandboxClient client = NandboxClient.get();
        client.connect(TOKEN, new ExtensionCustomLogic());
    }

    @Override
    public void onConnect(Nandbox.Api api) {
        this.api = api;
    }

    @Override
    public void onReceive(IncomingMessage incomingMsg) {
        if (incomingMsg == null) {
            return;
        }
        if (incomingMsg.getChat() == null || incomingMsg.getFrom() == null) {
            return;
        }

        String chatId = incomingMsg.getChat().getId();
        String userId = incomingMsg.getFrom().getId();
        String appId = incomingMsg.getAppId();
        Integer chatSettings = incomingMsg.getChatSettings();
        String reference = Utils.getUniqueId();

        String text = incomingMsg.getText();
        if (text == null) {
            text = "";
        }

        storeMessage(incomingMsg, text);

        String trimmed = text.trim();
        if (trimmed.length() > 0 && (trimmed.charAt(0) == '/' || trimmed.charAt(0) == '!')) {
            String cmdLine = trimmed;
            while (cmdLine.indexOf("  ") >= 0) {
                cmdLine = cmdLine.replace("  ", " ");
            }

            if (startsWithCommand(cmdLine, "/get_all_messages") || startsWithCommand(cmdLine, "!get_all_messages")) {
                requestAllMessages(chatId, userId, appId);
                return;
            }

            if (startsWithCommand(cmdLine, "/get_messages_by_user") || startsWithCommand(cmdLine, "!get_messages_by_user")) {
                String filterUserId = parseSecondToken(cmdLine);
                if (filterUserId == null || filterUserId.trim().length() == 0) {
                    sendText(chatId, "Usage: /get_messages_by_user <user_id>", userId, chatSettings, appId);
                    return;
                }
                requestMessagesByUser(chatId, userId, appId, filterUserId.trim());
                return;
            }
        }

        api.sendText(
                chatId,
                text,
                reference,
                null,
                userId,
                new Integer(0),
                Boolean.FALSE,
                chatSettings,
                null,
                null,
                null,
                appId
        );
    }

    @Override
    public void onExtensionDocResponse(ExtensionDocResponse extensionDocResponse) {
        if (extensionDocResponse == null) {
            return;
        }

        String ref = extensionDocResponse.getRef();
        if (ref == null) {
            return;
        }

        if (ref.startsWith("GETALL:")) {
            handleGetAll(ref, extensionDocResponse);
            return;
        }

        if (ref.startsWith("GETUSER:")) {
            handleGetUser(ref, extensionDocResponse);
        }
    }

    private void handleGetAll(String ref, ExtensionDocResponse extensionDocResponse) {
        String payload = ref.substring("GETALL:".length());
        int s1 = payload.indexOf(':');
        int s2 = payload.indexOf(':', s1 + 1);
        if (s1 < 0 || s2 < 0) {
            return;
        }
        String chatId = payload.substring(0, s1);
        String toUserId = payload.substring(s1 + 1, s2);
        String appId = payload.substring(s2 + 1);

        JSONArray rows = extractRows(extensionDocResponse);
        String out = formatMessages(rows, null);
        sendText(chatId, out, toUserId, null, appId);
    }

    private void handleGetUser(String ref, ExtensionDocResponse extensionDocResponse) {
        String payload2 = ref.substring("GETUSER:".length());
        int s1b = payload2.indexOf(':');
        int s2b = payload2.indexOf(':', s1b + 1);
        int s3b = payload2.indexOf(':', s2b + 1);
        if (s1b < 0 || s2b < 0 || s3b < 0) {
            return;
        }
        String chatId2 = payload2.substring(0, s1b);
        String toUserId2 = payload2.substring(s1b + 1, s2b);
        String appId2 = payload2.substring(s2b + 1, s3b);
        String filterUserId = payload2.substring(s3b + 1);

        JSONArray rows2 = extractRows(extensionDocResponse);
        String out2 = formatMessages(rows2, filterUserId);
        sendText(chatId2, out2, toUserId2, null, appId2);
    }

    private JSONArray extractRows(ExtensionDocResponse extensionDocResponse) {
        JSONArray rows = null;
        try {
            JSONObject result = extensionDocResponse.getResult();
            if (result != null) {
                Object dataObj = result.get("data");
                if (dataObj instanceof JSONArray) {
                    rows = (JSONArray) dataObj;
                }
            }
        } catch (Exception e) {
        }
        return rows;
    }

    private void storeMessage(IncomingMessage incomingMsg, String text) {
        if (api == null || incomingMsg == null) {
            return;
        }
        if (incomingMsg.getChat() == null || incomingMsg.getFrom() == null) {
            return;
        }

        String msgId = incomingMsg.getId();
        if (msgId == null || msgId.length() == 0) {
            msgId = Utils.getUniqueId();
        }

        String userId = incomingMsg.getFrom().getId();
        String chatId = incomingMsg.getChat().getId();
        String appId = incomingMsg.getAppId();

        JSONObject obj = new JSONObject();
        obj.put("id", msgId);
        obj.put("user_id", userId);
        obj.put("chat_id", chatId);
        obj.put("app_id", appId);
        obj.put("text", text);

        try {
            Long ts = incomingMsg.getTimestamp();
            if (ts != null) {
                obj.put("ts", ts);
            }
        } catch (Exception e) {
        }

        String ref = "SET:" + Utils.getUniqueId();
        DatabaseService.getInstance().set(api, obj, TABLE_MESSAGES, msgId, ref);
    }

    private void requestAllMessages(String chatId, String toUserId, String appId) {
        if (api == null) {
            return;
        }
        String ref = "GETALL:" + chatId + ":" + toUserId + ":" + appId + ":" + Utils.getUniqueId();
        DatabaseService.getInstance().set(api, TABLE_MESSAGES, ref);
    }

    private void requestMessagesByUser(String chatId, String toUserId, String appId, String filterUserId) {
        if (api == null) {
            return;
        }
        String ref = "GETUSER:" + chatId + ":" + toUserId + ":" + appId + ":" + filterUserId + ":" + Utils.getUniqueId();
        DatabaseService.getInstance().set(api, TABLE_MESSAGES, ref);
    }

    private String formatMessages(JSONArray rows, String filterUserId) {
        if (rows == null || rows.size() == 0) {
            if (filterUserId != null) {
                return "No stored messages found for user: " + filterUserId;
            }
            return "No stored messages found.";
        }

        StringBuffer sb = new StringBuffer();
        int count = 0;

        for (int i = 0; i < rows.size(); i++) {
            Object rowObj = rows.get(i);
            if (!(rowObj instanceof JSONObject)) {
                continue;
            }
            JSONObject row = (JSONObject) rowObj;
            String u = safeString(row.get("user_id"));
            if (filterUserId != null && (u == null || !filterUserId.equals(u))) {
                continue;
            }

            String text = safeString(row.get("text"));
            if (text == null) {
                text = "";
            }

            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(u != null ? u : "unknown");
            sb.append(": ");
            sb.append(text);

            count++;
            if (count >= MAX_RETURN_MESSAGES) {
                sb.append("\n...");
                break;
            }
        }

        if (count == 0) {
            if (filterUserId != null) {
                return "No stored messages found for user: " + filterUserId;
            }
            return "No stored messages found.";
        }

        return sb.toString();
    }

    private void sendText(String chatId, String text, String toUserId, Integer chatSettings, String appId) {
        if (api == null) {
            return;
        }
        api.sendText(
                chatId,
                text,
                Utils.getUniqueId(),
                null,
                toUserId,
                new Integer(0),
                Boolean.FALSE,
                chatSettings,
                null,
                null,
                null,
                appId
        );
    }

    private boolean startsWithCommand(String text, String command) {
        if (text == null || command == null) {
            return false;
        }
        if (text.equals(command)) {
            return true;
        }
        return text.startsWith(command + " ");
    }

    private String parseSecondToken(String cmdLine) {
        if (cmdLine == null) {
            return null;
        }
        int firstSpace = cmdLine.indexOf(' ');
        if (firstSpace < 0) {
            return null;
        }
        String rest = cmdLine.substring(firstSpace + 1).trim();
        if (rest.length() == 0) {
            return null;
        }
        int nextSpace = rest.indexOf(' ');
        if (nextSpace < 0) {
            return rest;
        }
        return rest.substring(0, nextSpace);
    }

    private String safeString(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return String.valueOf(o);
        } catch (Exception e) {
            return null;
        }
    }
}
