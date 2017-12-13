package de.ellpeck.rockbottom.assets.sound;

import de.ellpeck.rockbottom.api.assets.ISound;

public class EmptySound implements ISound{

    @Override
    public void play(){

    }

    @Override
    public void play(float pitch, float volume){

    }

    @Override
    public void play(float pitch, float volume, boolean loop){

    }

    @Override
    public void playAt(double x, double y, double z){

    }

    @Override
    public void playAt(float pitch, float volume, double x, double y, double z){

    }

    @Override
    public void playAt(float pitch, float volume, double x, double y, double z, boolean loop){

    }

    @Override
    public boolean isPlaying(){
        return false;
    }

    @Override
    public void stop(){

    }
}
