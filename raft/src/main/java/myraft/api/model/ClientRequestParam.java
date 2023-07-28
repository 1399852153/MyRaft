package myraft.api.model;

import myraft.api.command.Command;

import java.io.Serializable;

public class ClientRequestParam implements Serializable {

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
