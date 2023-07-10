package myraft.api.command;

public class GetCommand implements Command{

    private String key;

    public GetCommand() {
    }

    public GetCommand(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    @Override
    public String toString() {
        return "GetCommand{" +
            "key='" + key + '\'' +
            '}';
    }
}
