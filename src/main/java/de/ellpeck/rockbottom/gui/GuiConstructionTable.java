package de.ellpeck.rockbottom.gui;

import de.ellpeck.rockbottom.api.IGameInstance;
import de.ellpeck.rockbottom.api.RockBottomAPI;
import de.ellpeck.rockbottom.api.construction.BasicRecipe;
import de.ellpeck.rockbottom.api.entity.player.AbstractEntityPlayer;
import de.ellpeck.rockbottom.api.gui.GuiContainer;
import de.ellpeck.rockbottom.api.inventory.IInventory;
import de.ellpeck.rockbottom.api.util.reg.IResourceName;
import de.ellpeck.rockbottom.gui.component.ComponentConstruction;
import de.ellpeck.rockbottom.gui.container.ContainerInventory;
import de.ellpeck.rockbottom.net.packet.toserver.PacketTableConstruction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public class GuiConstructionTable extends GuiContainer{

    private final BiConsumer<IInventory, Integer> invCallback = (inv, slot) -> this.construction.organize();
    private ComponentConstruction construction;

    public GuiConstructionTable(AbstractEntityPlayer player){
        super(player, 158, 135);
    }

    @Override
    public void init(IGameInstance game){
        super.init(game);

        List<BasicRecipe> allRecipes = new ArrayList<>();
        allRecipes.addAll(RockBottomAPI.MANUAL_CONSTRUCTION_RECIPES);
        allRecipes.addAll(RockBottomAPI.CONSTRUCTION_TABLE_RECIPES);

        this.construction = new ComponentConstruction(this, 0, 0, this.width, 52, 8, 3, true, allRecipes, (recipe, recipeId) -> {
            if(RockBottomAPI.getNet().isClient()){
                RockBottomAPI.getNet().sendToServer(new PacketTableConstruction(game.getPlayer().getUniqueId(), recipeId, 1));
            }
            else{
                ContainerInventory.doInvBasedConstruction(game.getPlayer(), recipe, 1);
            }
        });
        this.components.add(this.construction);
    }

    @Override
    public void onOpened(IGameInstance game){
        super.onOpened(game);
        this.player.getInv().addChangeCallback(this.invCallback);
    }

    @Override
    public void onClosed(IGameInstance game){
        super.onClosed(game);
        this.player.getInv().removeChangeCallback(this.invCallback);
    }

    @Override
    public IResourceName getName(){
        return RockBottomAPI.createInternalRes("construction_table");
    }
}