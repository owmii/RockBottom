package de.ellpeck.rockbottom.net.chat.command;

import de.ellpeck.rockbottom.api.IGameInstance;
import de.ellpeck.rockbottom.api.RockBottomAPI;
import de.ellpeck.rockbottom.api.assets.font.FormattingCode;
import de.ellpeck.rockbottom.api.entity.player.AbstractEntityPlayer;
import de.ellpeck.rockbottom.api.item.Item;
import de.ellpeck.rockbottom.api.item.ItemInstance;
import de.ellpeck.rockbottom.api.net.chat.Command;
import de.ellpeck.rockbottom.api.net.chat.IChatLog;
import de.ellpeck.rockbottom.api.net.chat.ICommandSender;
import de.ellpeck.rockbottom.api.net.chat.component.ChatComponent;
import de.ellpeck.rockbottom.api.net.chat.component.ChatComponentText;
import de.ellpeck.rockbottom.api.net.chat.component.ChatComponentTranslation;
import de.ellpeck.rockbottom.api.util.Util;
import de.ellpeck.rockbottom.api.util.reg.IResourceName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CommandSpawnItem extends Command{

    private final List<String> itemAutocomplete = new ArrayList<>();

    public CommandSpawnItem(){
        super(RockBottomAPI.createInternalRes("spawn_item"), "Spawns an item into the player's inventory. Params: <mod_id/item_name> [amount] [meta]", 5, "spawn_item", "cheat");

        for(IResourceName name : RockBottomAPI.ITEM_REGISTRY.getUnmodifiable().keySet()){
            this.itemAutocomplete.add(name.toString());
        }
    }

    @Override
    public ChatComponent execute(String[] args, ICommandSender sender, String playerName, IGameInstance game, IChatLog chat){
        if(sender instanceof AbstractEntityPlayer){
            AbstractEntityPlayer player = (AbstractEntityPlayer)sender;

            Item item;
            if(args.length > 0){
                try{
                    IResourceName name = RockBottomAPI.createRes(args[0]);
                    item = RockBottomAPI.ITEM_REGISTRY.get(name);
                }
                catch(Exception e){
                    return new ChatComponentText(FormattingCode.RED+"'"+args[0]+"' isn't a valid item name!");
                }
            }
            else{
                return new ChatComponentText(FormattingCode.RED+"Specify an item!");
            }

            int amount = 1;
            if(args.length > 1){
                try{
                    amount = Util.clamp(Integer.parseInt(args[1]), 0, item.getMaxAmount());
                }
                catch(Exception ignored){
                }
            }

            int meta = 0;
            if(args.length > 2){
                try{
                    meta = Util.clamp(Integer.parseInt(args[2]), 0, item.getHighestPossibleMeta());
                }
                catch(Exception ignored){
                }
            }

            if(item != null){
                ItemInstance instance = new ItemInstance(item, amount, meta);
                ItemInstance left = player.getInv().addExistingFirst(instance, false);

                if(left != null && left.isEffectivelyEqual(instance)){
                    return new ChatComponentText(FormattingCode.RED+"Not enough space for ").append(new ChatComponentTranslation(item.getUnlocalizedName(instance))).append(new ChatComponentText(" x"+instance.getAmount()));
                }
                else{
                    return new ChatComponentText(FormattingCode.GREEN+"Spawned ").append(new ChatComponentTranslation(item.getUnlocalizedName(instance))).append(new ChatComponentText(" x"+instance.getAmount()));
                }
            }
            else{
                return new ChatComponentText(FormattingCode.RED+"An item with the name '"+args[0]+"' doesn't exist!");
            }
        }
        else{
            return new ChatComponentText(FormattingCode.RED+"Only players can spawn items!");
        }
    }

    @Override
    public List<String> getAutocompleteSuggestions(String[] args, int argNumber, ICommandSender sender, IGameInstance game, IChatLog chat){
        if(argNumber == 0){
            return this.itemAutocomplete;
        }
        else{
            return Collections.emptyList();
        }
    }
}
