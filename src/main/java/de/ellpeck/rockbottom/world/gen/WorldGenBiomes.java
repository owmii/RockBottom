package de.ellpeck.rockbottom.world.gen;

import de.ellpeck.rockbottom.api.Constants;
import de.ellpeck.rockbottom.api.GameContent;
import de.ellpeck.rockbottom.api.RockBottomAPI;
import de.ellpeck.rockbottom.api.util.Pos2;
import de.ellpeck.rockbottom.api.util.Util;
import de.ellpeck.rockbottom.api.util.reg.ResourceName;
import de.ellpeck.rockbottom.api.world.IChunk;
import de.ellpeck.rockbottom.api.world.IWorld;
import de.ellpeck.rockbottom.api.world.gen.INoiseGen;
import de.ellpeck.rockbottom.api.world.gen.IWorldGenerator;
import de.ellpeck.rockbottom.api.world.gen.biome.Biome;
import de.ellpeck.rockbottom.api.world.layer.TileLayer;

import java.util.*;

public class WorldGenBiomes implements IWorldGenerator{

    public static final ResourceName ID = ResourceName.intern("biomes");
    private static final int SIZE = 5;
    private static final int MAX_SIZE = 64;
    private final long[] layerSeeds = new long[MAX_SIZE];
    private final Random biomeRandom = new Random();

    private final Map<Biome, INoiseGen> biomeNoiseGens = new HashMap<>();

    @Override
    public void initWorld(IWorld world){
        Random rand = new Random(world.getSeed());
        for(int i = 0; i < MAX_SIZE; i++){
            this.layerSeeds[i] = rand.nextLong();
        }
    }

    @Override
    public boolean shouldGenerate(IWorld world, IChunk chunk){
        return true;
    }

    @Override
    public void generate(IWorld world, IChunk chunk){
        for(int x = 0; x < Constants.CHUNK_SIZE; x++){
            for(int y = 0; y < Constants.CHUNK_SIZE; y++){
                Biome biome = this.getBiome(world, chunk.getX()+x, chunk.getY()+y);
                chunk.setBiomeInner(x, y, biome);

                INoiseGen noise = this.getBiomeNoise(world, biome);
                for(TileLayer layer : TileLayer.getAllLayers()){
                    int height = world.getExpectedSurfaceHeight(layer, chunk.getX()+x);
                    chunk.setStateInner(layer, x, y, biome.getState(world, chunk, x, y, layer, noise, height));
                }
            }
        }
    }

    public Biome getBiome(IWorld world, int x, int y){
        int size = Math.min(MAX_SIZE, SIZE);
        int twoToSize = (int)Math.pow(2, size);

        Pos2 blobPos = this.getBlobPos(x, y, size, world);
        int perfectY = blobPos.getY()*twoToSize;

        List<Biome> possibleBiomes = new ArrayList<>();
        int totalWeight = 0;

        for(Biome biome : RockBottomAPI.BIOME_REGISTRY.values()){
            if(biome.canGenerateNaturally()){
                if(perfectY >= biome.getLowestY(world, x, y) && perfectY <= biome.getHighestY(world, x, y)){
                    possibleBiomes.add(biome);
                    totalWeight += biome.getWeight(world, x, y);
                }
            }
        }

        this.biomeRandom.setSeed(Util.scrambleSeed(blobPos.getX(), blobPos.getY(), world.getSeed())+world.getSeed());
        int chosenWeight = Util.floor(this.biomeRandom.nextDouble()*(double)totalWeight);

        Biome retBiome = null;

        int weight = 0;
        for(Biome biome : possibleBiomes){
            weight += biome.getWeight(world, x, y);
            if(weight >= chosenWeight){
                retBiome = biome;
                break;
            }
        }

        if(retBiome == null){
            retBiome = GameContent.BIOME_SKY;
            RockBottomAPI.logger().warning("Couldn't find a biome to generate for "+x+' '+y);
        }

        return retBiome.getVariationToGenerate(world, x, y);
    }

    private Pos2 getBlobPos(int x, int y, int size, IWorld world){
        Pos2 offset = new Pos2(x, y);
        for(int i = 0; i < size; i++){
            offset = this.zoomFromPos(offset, this.layerSeeds[i], world);
        }
        return offset;
    }

    public INoiseGen getBiomeNoise(IWorld world, Biome biome){
        return this.biomeNoiseGens.computeIfAbsent(biome, b -> RockBottomAPI.getApiHandler().makeSimplexNoise(b.getBiomeSeed(world)));
    }

    private Pos2 zoomFromPos(Pos2 pos, long seed, IWorld world){
        boolean xEven = (pos.getX() & 1) == 0;
        boolean yEven = (pos.getY() & 1) == 0;

        int halfX = pos.getX()/2;
        int halfY = pos.getY()/2;

        if(xEven && yEven){
            return new Pos2(halfX, halfY);
        }
        else{
            this.biomeRandom.setSeed(Util.scrambleSeed(pos.getX(), pos.getY(), world.getSeed())+seed);
            int offX = this.biomeRandom.nextBoolean() ? (pos.getX() < 0 ? -1 : 1) : 0;
            int offY = this.biomeRandom.nextBoolean() ? (pos.getY() < 0 ? -1 : 1) : 0;

            if(xEven){
                return new Pos2(halfX, halfY+offY);
            }
            else if(yEven){
                return new Pos2(halfX+offX, halfY);
            }
            else{
                return new Pos2(halfX+offX, halfY+offY);
            }
        }
    }

    @Override
    public int getPriority(){
        return 10000;
    }
}
