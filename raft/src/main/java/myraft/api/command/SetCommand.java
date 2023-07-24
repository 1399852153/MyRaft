package myraft.api.command;

/**
 * 写操作，把一个key设置为value
 * */
public class SetCommand implements Command{

    private String key;
    private String value;

    public SetCommand() {
    }

    public SetCommand(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "SetCommand{" +
            "key='" + key + '\'' +
            ", value='" + value + '\'' +
            '}';
    }
}

