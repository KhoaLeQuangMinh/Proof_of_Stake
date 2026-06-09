package com.tcl.backend.messaging.mempool;

import java.util.List;
import com.rabbitmq.client.Channel;
import com.tcl.backend.model.OutboundTransactionMessage;

public class MempoolBatch {
    private List<OutboundTransactionMessage> transactions;
    private long batchTimestamp;
    Channel channel;
    List<Long> deliveryTags;    
    
    public MempoolBatch(List<OutboundTransactionMessage> transactions, Channel channel, List<Long> deliveryTags){
        this.transactions = transactions;
        this.channel = channel;
        this.deliveryTags = deliveryTags;
        batchTimestamp = System.currentTimeMillis();
    }
    public List<OutboundTransactionMessage> getTransactions() {
        return transactions;
    }
    public long getBatchTimestamp() {
        return batchTimestamp;
    }
}
