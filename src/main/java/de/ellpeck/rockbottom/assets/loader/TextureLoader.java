package de.ellpeck.rockbottom.assets.loader;

import com.google.common.base.Charsets;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.ellpeck.rockbottom.api.RockBottomAPI;
import de.ellpeck.rockbottom.api.assets.IAssetLoader;
import de.ellpeck.rockbottom.api.assets.IAssetManager;
import de.ellpeck.rockbottom.api.assets.ITexture;
import de.ellpeck.rockbottom.api.mod.IMod;
import de.ellpeck.rockbottom.api.util.Util;
import de.ellpeck.rockbottom.api.util.reg.IResourceName;
import de.ellpeck.rockbottom.assets.AssetManager;
import de.ellpeck.rockbottom.assets.tex.Texture;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TextureLoader implements IAssetLoader<ITexture>{

    private final Map<String, Map<String, JsonElement>> additionalDataCache = new HashMap<>();

    @Override
    public IResourceName getAssetIdentifier(){
        return RockBottomAPI.createInternalRes("tex");
    }

    @Override
    public ITexture loadAsset(IAssetManager manager, IResourceName resourceName, String path, JsonElement element, String elementName, IMod loadingMod) throws Exception{
        ITexture texture = this.makeTexture(element, path);

        RockBottomAPI.logger().config("Loaded texture "+resourceName+" for mod "+loadingMod.getDisplayName());
        return texture;
    }

    private Texture makeTexture(JsonElement element, String path) throws Exception{
        String resPath;
        Map<String, JsonElement> additionalData = null;
        List<ITexture> variations = null;

        if(element.isJsonObject()){
            JsonObject object = element.getAsJsonObject();
            resPath = path+object.get("path").getAsString();

            if(object.has("variations")){
                JsonArray varArray = object.get("variations").getAsJsonArray();
                for(JsonElement variation : varArray){
                    String dataPath = path+variation.getAsString();

                    if(variations == null){
                        variations = new ArrayList<>();
                    }

                    variations.add(new Texture(AssetManager.getResourceAsStream(dataPath)));
                }
            }

            if(object.has("data")){
                String dataPath = path+object.get("data").getAsString();
                if(additionalData == null){
                    additionalData = this.additionalDataCache.get(dataPath);
                    if(additionalData == null){
                        InputStreamReader reader = new InputStreamReader(AssetManager.getResourceAsStream(dataPath), Charsets.UTF_8);
                        JsonObject main = Util.JSON_PARSER.parse(reader).getAsJsonObject();
                        if(main != null){
                            additionalData = new HashMap<>();

                            for(Map.Entry<String, JsonElement> entry : main.entrySet()){
                                additionalData.put(entry.getKey(), entry.getValue());
                            }

                            this.additionalDataCache.put(dataPath, additionalData);
                        }
                    }
                }
            }
        }
        else{
            resPath = path+element.getAsString();
        }

        Texture texture = new Texture(AssetManager.getResourceAsStream(resPath));

        if(additionalData != null){
            texture.setAdditionalData(additionalData);
        }

        if(variations != null){
            texture.setVariations(variations);
        }

        return texture;
    }

    @Override
    public Map<IResourceName, ITexture> dealWithSpecialCases(IAssetManager manager, String resourceName, String path, JsonElement element, String elementName, IMod loadingMod) throws Exception{
        if("subtexture".equals(elementName)){
            Map<IResourceName, ITexture> subTextures = new HashMap<>();

            JsonObject object = element.getAsJsonObject();
            Texture main = this.makeTexture(object.get("file"), path);

            for(Map.Entry<String, JsonElement> entry : object.entrySet()){
                String key = entry.getKey();
                if(!"file".equals(key)){
                    JsonArray array = entry.getValue().getAsJsonArray();

                    String resName;
                    if("*".equals(key)){
                        resName = resourceName.substring(0, resourceName.length()-1);
                    }
                    else{
                        resName = resourceName+key;
                    }
                    IResourceName res = RockBottomAPI.createRes(loadingMod, resName);

                    ITexture texture = main.getSubTexture(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt(), array.get(3).getAsInt());
                    subTextures.put(res, texture);

                    RockBottomAPI.logger().config("Loaded subtexture "+res+" for mod "+loadingMod.getDisplayName());
                }
            }

            return subTextures;
        }
        else{
            return null;
        }
    }

    @Override
    public void finalize(IAssetManager manager){
        this.additionalDataCache.clear();
    }
}
