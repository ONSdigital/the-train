package com.github.davidcarboni.thetrain.json.request;

public class MoveDetail {
    public String source;
    public String target;

    public MoveDetail(String sourceUri, String targetUri) {
        this.source = sourceUri;
        this.target = targetUri;
    }
}