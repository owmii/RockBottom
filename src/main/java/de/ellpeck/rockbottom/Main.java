package de.ellpeck.rockbottom;

import de.ellpeck.rockbottom.util.LogSystem;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.newdawn.slick.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;

public final class Main{

    public static CustomClassLoader classLoader;

    public static File gameDir;
    public static File tempDir;
    public static File unpackedModsDir;

    public static int width;
    public static int height;
    public static boolean fullscreen;

    public static void main(String[] args){
        LogSystem.init();

        Log.info("Found launch args "+Arrays.toString(args));

        OptionParser parser = new OptionParser();
        OptionSpec<File> optionGameDir = parser.accepts("gameDir").withRequiredArg().ofType(File.class).defaultsTo(new File(".", "rockbottom"));
        OptionSpec<File> optionTempDir = parser.accepts("tempDir").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> optionUnpackedDir = parser.accepts("unpackedModsDir").withRequiredArg().ofType(File.class);
        OptionSpec<Integer> optionWidth = parser.accepts("width").withRequiredArg().ofType(Integer.class).defaultsTo(1280);
        OptionSpec<Integer> optionHeight = parser.accepts("height").withRequiredArg().ofType(Integer.class).defaultsTo(720);
        OptionSpec optionFullscreen = parser.accepts("fullscreen");

        OptionSet options = parser.parse(args);
        gameDir = options.valueOf(optionGameDir);
        Log.info("Setting game folder to "+gameDir);

        tempDir = options.valueOf(optionTempDir);
        Log.info("Setting temp folder to "+tempDir);

        unpackedModsDir = options.valueOf(optionUnpackedDir);
        if(unpackedModsDir != null){
            Log.info("Setting unpacked mods folder to "+unpackedModsDir);
        }

        width = options.valueOf(optionWidth);
        height = options.valueOf(optionHeight);
        fullscreen = options.has(optionFullscreen);

        try{
            URLClassLoader loader = (URLClassLoader)Main.class.getClassLoader();

            classLoader = new CustomClassLoader(loader.getURLs(), loader);
            Thread.currentThread().setContextClassLoader(classLoader);

            Log.info("Replacing class loader "+loader+" with new loader "+classLoader);

            loader.close();
        }
        catch(Exception e){
            throw new RuntimeException("Failed to override original class loader", e);
        }

        try{
            Class gameClass = Class.forName("de.ellpeck.rockbottom.RockBottom", false, classLoader);
            Method method = gameClass.getMethod("init");
            method.invoke(null);
        }
        catch(Exception e){
            throw new RuntimeException("Could not initialize game", e);
        }
    }

    public static class CustomClassLoader extends URLClassLoader{

        public CustomClassLoader(URL[] urls, ClassLoader parent){
            super(urls, parent);
        }

        @Override
        public void addURL(URL url){
            super.addURL(url);
        }

        @Override
        protected String findLibrary(String libName){
            String mapped = System.mapLibraryName(libName);
            InputStream stream = this.getResourceAsStream("natives/"+mapped);
            if(stream != null){
                String lib = loadLib(stream, mapped);
                if(lib != null && !lib.isEmpty()){
                    return lib;
                }
            }
            return super.findLibrary(libName);
        }
    }

    private static String loadLib(InputStream in, String libName){
        try{
            if(!tempDir.exists()){
                tempDir.mkdirs();
            }

            File temp = new File(tempDir, libName);
            if(temp.exists()){
                Log.info("File "+temp+" already exists, using existing version");
                return temp.getAbsolutePath();
            }
            else{
                Log.info("Creating temporary file "+temp);
            }

            FileOutputStream out = new FileOutputStream(temp);
            byte[] buffer = new byte[65536];

            while(true){
                int bufferSize = in.read(buffer, 0, buffer.length);

                if(bufferSize != -1){
                    out.write(buffer, 0, bufferSize);
                }
                else{
                    break;
                }
            }

            out.close();

            return temp.getAbsolutePath();
        }
        catch(IOException e){
            throw new RuntimeException("Couldn't load lib with name "+libName, e);
        }
    }
}
