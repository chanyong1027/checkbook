package com.checkbook.client.aladin;

public enum AladinListQueryType {
    BESTSELLER("Bestseller"),
    ITEM_NEW_SPECIAL("ItemNewSpecial");

    private final String value;

    AladinListQueryType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
