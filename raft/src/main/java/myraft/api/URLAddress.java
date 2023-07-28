package myraft.api;

import java.io.Serializable;

public class URLAddress extends myrpc.common.model.URLAddress implements Serializable {

    public URLAddress(String host, int port) {
        super(host, port);
    }

    public URLAddress() {
    }
}
