package de.ellpeck.game.gui.menu;

import de.ellpeck.game.Game;
import de.ellpeck.game.Settings;
import de.ellpeck.game.assets.AssetManager;
import de.ellpeck.game.assets.font.Font;
import de.ellpeck.game.gui.Gui;
import de.ellpeck.game.gui.component.ComponentButton;
import de.ellpeck.game.gui.component.ComponentKeybind;
import de.ellpeck.game.world.entity.player.EntityPlayer;
import org.newdawn.slick.Graphics;

public class GuiKeybinds extends Gui{

    public int activeKeybind = -1;

    public GuiKeybinds(EntityPlayer player, Gui parent){
        super(player, 304, 100, parent);
    }

    @Override
    public void initGui(Game game){
        super.initGui(game);

        int x = 0;
        int y = 0;
        for(int i = 0; i < game.settings.keybinds.size(); i++){
            Settings.Keybind bind = game.settings.keybinds.get(i);
            this.components.add(new ComponentKeybind(this, i, this.guiLeft+x, this.guiTop+y, bind));

            y += 20;
            if(i == 2){
                x += 154;
                y = 0;
            }
        }

        this.components.add(new ComponentButton(this, -1, this.guiLeft+this.sizeX/2-40, this.guiTop+this.sizeY-16, 80, 16, game.assetManager.localize("button.back")));
    }

    @Override
    public void render(Game game, AssetManager manager, Graphics g){
        super.render(game, manager, g);

        Font font = manager.getFont();
        font.drawCenteredString(this.guiLeft+this.sizeX/2, this.guiTop+this.sizeY+15, "Press &6F&4 while in the inventory to get items!", 0.25F, false);
        font.drawCenteredString(this.guiLeft+this.sizeX/2, this.guiTop+this.sizeY+25, "Press &6F1&4 to open the debug menu!", 0.25F, false);
    }

    @Override
    public boolean onButtonActivated(Game game, int button){
        if(button == -1){
            game.guiManager.openGui(this.parent);
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseAction(Game game, int button, float x, float y){
        if(!super.onMouseAction(game, button, x, y)){
            this.activeKeybind = -1;
        }
        return true;
    }
}
