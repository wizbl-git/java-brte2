package com.wizbl.common.overlay.client;

import com.wizbl.api.DatabaseGrpc;
import com.wizbl.api.GrpcAPI;
import com.wizbl.protos.Protocol;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class DatabaseGrpcClient {
    private ManagedChannel channel;
    private final DatabaseGrpc.DatabaseBlockingStub databaseBlockingStub;

    public DatabaseGrpcClient(String host, int port){
        channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext(true).build();
        databaseBlockingStub = DatabaseGrpc.newBlockingStub(channel);
    }

    public DatabaseGrpcClient(String host){
        channel = ManagedChannelBuilder.forTarget(host)
                .usePlaintext(true)
                .build();
        databaseBlockingStub = DatabaseGrpc.newBlockingStub(channel);
    }

    public Protocol.Block getBlock(long blockNum) {
        if (blockNum < 0) {
            return databaseBlockingStub.getNowBlock(GrpcAPI.EmptyMessage.newBuilder().build());
        }
        GrpcAPI.NumberMessage.Builder builder = GrpcAPI.NumberMessage.newBuilder();
        builder.setNum(blockNum);
        return databaseBlockingStub.getBlockByNum(builder.build());
    }

    public void shutdown() {
        channel.shutdown();
    }

    public Protocol.DynamicProperties getDynamicProperties() {
        return databaseBlockingStub.getDynamicProperties(GrpcAPI.EmptyMessage.newBuilder().build());
    }
}
