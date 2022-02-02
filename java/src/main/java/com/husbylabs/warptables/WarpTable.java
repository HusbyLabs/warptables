package com.husbylabs.warptables;

import com.husbylabs.warptables.packets.ClientHandshake;
import com.husbylabs.warptables.providers.Provider;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

/**
 * The WarpTables client for writing and reading data
 *
 * @author Noah Husby
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public class WarpTable {
    @Getter
    protected final Provider provider;

    @Getter
    protected UUID uniqueId = new UUID(0L, 0L);

    private Map<Integer, WarpTableEntry> fields = new HashMap<>();
    private Map<String, Integer> fieldTags = new HashMap<>();

    ScheduledFuture<?> providerConnectionThread = null;

    /**
     * Start the WarpTable connection
     */
    public void start() {
        WarpTableAPI.getLogger().info("Starting WarpTable client");
        openProvider();
        ClientHandshake handshake = ClientHandshake.newBuilder()
                .setProtocol(Constants.PROTO_VER)
                .build();
        provider.send(PacketRegistry.encode(ClientHandshake.class, handshake.toByteArray()));
    }

    protected void openProvider() {
        provider.init(this);
        WarpTableAPI.getLogger().info("Opening provider [" + provider.getName() + "]");
        provider.open();
    }

    public void setFieldInfo(FieldInfo info) {

    }

    public void setFieldsInfo(Collection<FieldInfo> info) {

    }

    /**
     * Gets a {@link WarpTableEntry} by its registration id
     *
     * @param id The registration id
     * @return {@link WarpTableEntry} if exists, null if not
     */
    public WarpTableEntry get(int id) {
        return fields.get(id);
    }

    /**
     * Gets a {@link WarpTableEntry} by its tag
     * @param tag The tag
     * @return {@link WarpTableEntry} if exists, otherwise a new entry will be created
     */
    public WarpTableEntry get(@NonNull String tag) {
        if(!fieldTags.containsKey(tag)) {
            WarpTableEntry entry = create();
            fieldTags.put(tag, entry.getId());
            return entry;
        }
        return get(fieldTags.get(tag));
    }

    public WarpTableEntry create() {
        int id = fields.size();
        while(fields.containsKey(id)) {
            id++;
        }
        WarpTableEntry entry = new WarpTableEntry(this, id);
        fields.put(id, entry);
        return entry;
    }

    /**
     * [Internal use only] Parses an incoming message
     * @param data The incoming data
     */
    public void onMessage(byte[] data) {
        PacketMetadata metadata = PacketRegistry.decode(data);

    }
}