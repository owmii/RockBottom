package de.ellpeck.game.data.set.part;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;

public class PartBoolean extends BasicDataPart<Boolean>{

    public PartBoolean(String name, Boolean data){
        super(name, data);
    }

    public PartBoolean(String name){
        super(name);
    }

    @Override
    public void write(DataOutput stream) throws Exception{
        stream.writeBoolean(this.data);
    }

    @Override
    public void read(DataInput stream) throws Exception{
        this.data = stream.readBoolean();
    }
}
