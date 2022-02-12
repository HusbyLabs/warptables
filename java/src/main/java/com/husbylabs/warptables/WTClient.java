/*
 * MIT License
 *
 * Copyright (c) 2022 Husby Labs
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 *  OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 *  BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE
 */

package com.husbylabs.warptables;

import com.husbylabs.warptables.packets.ClientHandshake;
import com.husbylabs.warptables.packets.FetchTableRequest;
import com.husbylabs.warptables.packets.HandshakeGrpc;
import com.husbylabs.warptables.packets.ServerHandshake;
import com.husbylabs.warptables.packets.SubscribeTableRequest;
import com.husbylabs.warptables.packets.TableGrpc;
import com.husbylabs.warptables.packets.TableResponse;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.CallStreamObserver;
import lombok.Getter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * The WarpTables client for writing and reading data
 *
 * @author Noah Husby
 */
public class WTClient extends WarpTableInstance {

    private final ManagedChannel channel;
    @Getter
    private boolean connected = false;
    private boolean autoConnect = false;
    private boolean started = false;
    private Thread autoConnectThread;

    private int clientId = -1;

    private HandshakeGrpc.HandshakeBlockingStub handshakeStub;
    private TableGrpc.TableBlockingStub tableStub;

    protected WTClient(InetSocketAddress address) {
        super(address);
        channel = ManagedChannelBuilder.forAddress(address.getHostName(), address.getPort())
                .usePlaintext()
                .build();
    }

    /**
     * Start the WarpTable connection
     */
    @Override
    public void start() throws IOException, InterruptedException {
        long timeout = System.currentTimeMillis() + 5000;
        started = true;
        // Attempt 5000ms blocking connection
        while (timeout > System.currentTimeMillis() && !connected) {
            if (channel.getState(true) == ConnectivityState.READY) {
                attemptHandshake();
            }
        }
        if (connected) {
            return;
        }

        // Error handling depending on autoConnect option
        if (autoConnect) {
            handleAutoConnect();
        } else {
            throw new IOException("The WarpTables server is not accessible.");
        }
    }

    private void handleAutoConnect() {
        if (autoConnectThread != null && autoConnectThread.isAlive()) {
            return;
        }
        autoConnectThread = new Thread(() -> {
            while (!connected) {
                if (channel.getState(true) == ConnectivityState.READY) {
                    attemptHandshake();
                }
            }
        });
        autoConnectThread.start();
    }

    /**
     * Attempts a client -> server handshake
     */
    private void attemptHandshake() {
        handshakeStub = HandshakeGrpc.newBlockingStub(channel);
        ClientHandshake clientHandshake = ClientHandshake.newBuilder()
                .setProtocol(Constants.PROTO_VER)
                .build();
        ServerHandshake serverHandshake = handshakeStub.initiateHandshake(clientHandshake);
        if (serverHandshake.getSupported()) {
            connected = true;
            clientId = serverHandshake.getClientId();
            System.out.println("Connected w/ Client Id: " + clientId);
            tableStub = TableGrpc.newBlockingStub(channel);
            handleClientConnection();
            new Thread(this::registerStreamers).start();
        }
    }

    private void handleClientConnection() {
        channel.notifyWhenStateChanged(channel.getState(false), () -> {
            System.out.println("State change: " + channel.getState(false));
            if (channel.getState(false) != ConnectivityState.READY && connected) {
                connected = false;
                if (autoConnect && started) {
                    handleAutoConnect();
                }
            }
            handleClientConnection();
        });
    }

    /**
     * Enable the auto connect feature.
     * The auto connect feature will attempt to keep the client and server connected as long as {@link #stop()} hasn't been called.
     */
    public void enableAutoConnect() {
        autoConnect = true;
    }

    /**
     * Disable the auto connect feature.
     */
    public void disableAutoConnect() {
        autoConnect = false;
    }

    /**
     * Blocks current thread until the client is connected
     */
    public void awaitConnection() {
        while (!connected) {
        }
    }

    public void awaitTermination() {
        try {
            channel.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void stop() {
        WarpTablesAPI.getLogger().info("Stopping WarpTable client");
        autoConnectThread.interrupt();
        autoConnectThread = null;
        started = false;
    }

    @Override
    public Table getTable(String tableName) {
        // Check server for table information
        if (!tableIdByName.containsKey(tableName)) {
            FetchTableRequest tableRequest = FetchTableRequest.newBuilder()
                    .setClientId(clientId)
                    .setName(tableName)
                    .build();
            TableResponse tableResponse = tableStub.fetch(tableRequest);
            return handleTable(tableResponse.getName(), tableResponse.getTableId());
        }
        return getTable(tableIdByName.get(tableName));
    }

    private void registerStreamers() {
        TableGrpc.newStub(channel).subscribe(
                SubscribeTableRequest.newBuilder().setClientId(clientId).build(),
                new TableResponseCallback()
        );
    }

    /*
     * Callbacks
     */

    private abstract static class Callback<T> extends CallStreamObserver<T> {

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setOnReadyHandler(Runnable onReadyHandler) {

        }

        @Override
        public void disableAutoInboundFlowControl() {

        }

        @Override
        public void request(int count) {

        }

        @Override
        public void setMessageCompression(boolean enable) {

        }
    }

    private class TableResponseCallback extends Callback<TableResponse> {

        @Override
        public void onNext(TableResponse value) {
            System.out.printf("[Client: %s, Table Name: %s, Table ID: %s]\n", clientId, value.getName(), value.getTableId());
        }

        @Override
        public void onError(Throwable t) {

        }

        @Override
        public void onCompleted() {

        }
    }
}
