package oram.client.module;

import oram.utils.Operation;

public class Round1RequestItem {
    private final Operation operation;
    private final int bid;

    public Round1RequestItem(Operation operation, int bid) {
        this.operation = operation;
        this.bid = bid;
    }

    public Operation getOperation() {
        return operation;
    }

    public int getBid() {
        return bid;
    }
}
