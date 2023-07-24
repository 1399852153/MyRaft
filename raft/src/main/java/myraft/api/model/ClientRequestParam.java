package myraft.api.model;

import myraft.api.command.Command;

public class ClientRequestParam {

    private Command command;

    public ClientRequestParam() {
    }

    public ClientRequestParam(Command command) {
        this.command = command;
    }

    public Command getCommand() {
        return command;
    }
}
