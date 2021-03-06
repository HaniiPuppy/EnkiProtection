package com.enkigaming.minecraft.forge.enkiprotection.claim;

import com.enkigaming.mcforge.enkilib.EnkiLib;
import com.enkigaming.mcforge.enkilib.exceptions.UnableToParseTreeNodeException;
import com.enkigaming.mcforge.enkilib.filehandling.TreeFileHandler.TreeNode;
import com.enkigaming.minecraft.forge.enkiprotection.EnkiProtection;
import com.enkigaming.minecraft.forge.enkiprotection.exceptions.ObjectFinishedWithException;
import com.enkigaming.minecraft.forge.enkiprotection.playerpower.PlayerPower;
import com.enkigaming.minecraft.forge.enkiprotection.registry.exceptions.RevokingMorePowerThanAvailableException;
import com.enkigaming.minecraft.forge.enkiprotection.registry.exceptions.RevokingMorePowerThanGrantedException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.entity.player.EntityPlayer;

// Need to check over power revocation queueing.

public class ClaimPower
{
    protected static class QueuedRevocation
    {
        public QueuedRevocation(UUID revokingPlayerId, Date forceRevokeOn, int amount)
        { this(revokingPlayerId, new Date(), forceRevokeOn, amount); }
        
        public QueuedRevocation(UUID revokingPlayerId, Date whenStarted, Date forceRevokeOn, int amount)
        {
            this.started = whenStarted;
            this.forceRevokeOn = new Date(forceRevokeOn.getTime());
            this.amount = amount;
            this.playerId = revokingPlayerId;
        }
        
        protected final Date started;
        protected Date forceRevokeOn;
        protected int amount;
        protected final UUID playerId;
        
        protected final Object revokeOnLock = new Object();
        protected final Object amountLock = new Object();
        
        public Date getWhenStarted()
        { return new Date(started.getTime()); }
        
        public Date getWhenToForceRevoke()
        {
            synchronized(revokeOnLock)
            { return new Date(forceRevokeOn.getTime()); }
        }
        
        public int getAmount()
        {
            synchronized(amountLock)
            { return amount; }
        }
        
        public UUID getPlayerId()
        { return playerId; }
        
        public Date setWhenToForceRevoke(Date newDate)
        {
            synchronized(revokeOnLock)
            {
                Date old = forceRevokeOn;
                forceRevokeOn = new Date(newDate.getTime());
                return old;
            }
        }
        
        public int setAmount(int newAmount)
        {
            synchronized(amountLock)
            {
                int old = amount;
                amount = newAmount;
                return old;
            }
        }
        
        public int removeFromAmount(int amountToRemove)
        {
            synchronized(amountLock)
            {
                int old = amount;
                amount -= amountToRemove;
                return old;
            }
        }
        
        public int addToAmount(int amountToAdd)
        {
            synchronized(amountLock)
            {
                int old = amount;
                amount += amountToAdd;
                return old;
            }
        }
        
        public boolean isDue()
        {
            synchronized(revokeOnLock)
            { return new Date().after(forceRevokeOn); }
        }
    }
    
    // The following subclasses stand as a testament to why we should have per-object/OO-style events. If I import the
    // events system from my personal library into EnkiLib/EnkiCore, replace this to use that instead.
    
    static abstract class ForceRevokeListener
    { public abstract void onForceRevoke(UUID playerRevoking, int amountBeingRevoked, int amountMoreThanAvailableBeingRevoked); }
    
    static abstract class PowerUsedGetter
    { public abstract int getPowerUsed(); }
    
    static abstract class QueuedRevocationExpirationListener
    { public abstract void onQueuedRevocationExpiration(UUID playerId, int amount); }
    
    public ClaimPower()
    {
        powerGrants = new HashMap<UUID, Integer>();
        revokeQueue = new HashMap<UUID, QueuedRevocation>();
    }
    
    public ClaimPower(TreeNode node) throws UnableToParseTreeNodeException
    {
        this();
        
        for(TreeNode subNode : node.getChildren())
        {
            if(subNode.getName().toUpperCase().startsWith(powerGrantsTag.toUpperCase()))
            {
                for(TreeNode powerGrantNode : subNode.getChildren())
                {
                    UUID playerId = getPlayerIdFromNodeName(powerGrantNode.getName().trim());
                    
                    if(playerId == null) // If this tree node isn't a player id, and thus can't be used.
                    {
                        System.out.println("Could not parse \"" + powerGrantNode.getName() + "\" into a UUID for power grants.");
                        continue;
                    }
                    
                    for(TreeNode grantSubNode : powerGrantNode.getChildren())
                    {
                        if(grantSubNode.getName().toUpperCase().startsWith(amountTag.toUpperCase()))
                        {
                            Integer amount = getAmountFromAmountNodeName(grantSubNode.getName());
                            
                            if(amount == null)
                            {
                                System.out.println("Could not parse \"" + grantSubNode.getName() + "\" into an integer.");
                                continue;
                            }
                            
                            powerGrants.put(playerId, amount);
                        }
                    }
                }
            }
            else if(subNode.getName().toUpperCase().startsWith(revokeQueueTag.toUpperCase()))
            {
                for(TreeNode queuedRevocationNode : subNode.getChildren())
                {
                    UUID playerId = getPlayerIdFromNodeName(queuedRevocationNode.getName().trim());
                    
                    if(playerId == null) // If this tree node isn't a player id, and thus can't be used.
                    {
                        System.out.println("Could not parse \"" + queuedRevocationNode.getName() + "\" into a UUID for queued revocations. ");
                        continue;
                    }
                    
                    Date whenStarted = null;
                    Date whenToExpire = null;
                    int amount = 0;
                    
                    for(TreeNode revocationSubNode : queuedRevocationNode.getChildren())
                    {
                        if(revocationSubNode.getName().toUpperCase().startsWith(amountTag.toUpperCase()))
                        {
                            Integer currentAmount = getAmountFromAmountNodeName(revocationSubNode.getName());
                            
                            if(currentAmount == null)
                            {
                                System.out.println("Could not parse \"" + revocationSubNode.getName() + "\" into an integer.");
                                continue;
                            }
                            
                            amount = currentAmount;
                        }
                        else if(revocationSubNode.getName().toUpperCase().startsWith(dateStartedTag.toUpperCase()))
                        {
                            Long currentWhenStarted = getTimeFromNodeName(revocationSubNode.getName());
                            
                            if(currentWhenStarted == null)
                            {
                                System.out.println("Could not parse \"" + revocationSubNode.getName() + "\" into a long integer.");
                                continue;
                            }
                            
                            whenStarted = new Date(currentWhenStarted);
                        }
                        else if(revocationSubNode.getName().toUpperCase().startsWith(dateDueTag.toUpperCase()))
                        {
                            Long currentWhenToExpire = getTimeFromNodeName(revocationSubNode.getName());
                            
                            if(currentWhenToExpire == null)
                            {
                                System.out.println("Could not parse \"" + revocationSubNode.getName() + "\" into a long integer.");
                                continue;
                            }
                            
                            whenStarted = new Date(currentWhenToExpire);
                        }
                    }
                    
                    QueuedRevocation revocation = new QueuedRevocation(playerId, whenStarted, whenToExpire, amount);
                    revokeQueue.put(playerId, revocation);
                }
            }
        }
    }
    
    protected final Map<UUID, Integer> powerGrants;
    protected final Map<UUID, QueuedRevocation> revokeQueue; // synchronized with powerGrants
    
    PowerUsedGetter powerUsedGetter = null;
    
    final Collection<ForceRevokeListener> forceRevokeListeners = new ArrayList<ForceRevokeListener>();
    final Collection<QueuedRevocationExpirationListener> queuedRevocationExpirationListeners = new ArrayList<QueuedRevocationExpirationListener>();
    
    protected static final String powerTag = "Power";
    protected static final String powerGrantsTag = "Power Grants";
    protected static final String revokeQueueTag = "Revoke Queue";
    protected static final String amountTag = "Amount";
    protected static final String dateStartedTag = "Started";
    protected static final String dateDueTag = "Date due";
    protected static final String unixTimeTag = "(Unix time, milliseconds)";
    protected static final String separator = ": ";
    
    protected boolean isUsable = true;
    
    public TreeNode toTreeNode()
    {
        TreeNode baseNode = new TreeNode(powerTag + separator);
        TreeNode powerGrantsNode = new TreeNode(powerGrantsTag + separator);
        TreeNode revokeQueueNode = new TreeNode(revokeQueueTag + separator);
        
        baseNode.addChild(powerGrantsNode);
        baseNode.addChild(revokeQueueNode);
        
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            for(Entry<UUID, Integer> entry : powerGrants.entrySet())
            {
                String lastRecordedName = EnkiLib.getLastRecordedNameOf(entry.getKey());
                String nameString = "";
                
                if(lastRecordedName != null)
                    nameString += lastRecordedName + separator;
                    
                TreeNode grantNode = new TreeNode(nameString + entry.getKey().toString());
                powerGrantsNode.addChild(grantNode);
                
                grantNode.addChild(new TreeNode(amountTag + separator + entry.getValue().toString()));
                grantNode.addChild(new TreeNode(""));
            }
            
            for(Entry<UUID, QueuedRevocation> entry : revokeQueue.entrySet())
            {
                String lastRecordedName = EnkiLib.getLastRecordedNameOf(entry.getKey());
                String nameString = "";
                
                if(lastRecordedName != null)
                    nameString += lastRecordedName + separator;
                
                TreeNode queuedRevokeNode = new TreeNode(nameString + entry.getKey().toString());
                revokeQueueNode.addChild(queuedRevokeNode);
                
                queuedRevokeNode.addChild(new TreeNode(amountTag + separator + entry.getValue().getAmount()));
                queuedRevokeNode.addChild(new TreeNode(dateStartedTag + " " + unixTimeTag + separator + entry.getValue().getWhenStarted().getTime()));
                queuedRevokeNode.addChild(new TreeNode(dateDueTag + " " + unixTimeTag + separator + entry.getValue().getWhenToForceRevoke().getTime()));
                queuedRevokeNode.addChild(new TreeNode(""));
            }
        }
        
        return baseNode;
    }
    
    public static ClaimPower fromTreeNode(TreeNode node) throws UnableToParseTreeNodeException
    { return new ClaimPower(node); }
    
    protected static UUID getPlayerIdFromNodeName(String nodeName)
    {
        UUID playerId;
        String name = nodeName;
        String[] nodeNameParts = nodeName.split("\\Q" + separator + "\\E");
        
        if(nodeNameParts.length >= 2)
            name = nodeNameParts[nodeNameParts.length - 1];
        
        try
        { playerId = UUID.fromString(name.trim()); }
        catch(IllegalArgumentException exception)
        { return null; }
        
        return playerId;
    }
    
    protected static Integer getAmountFromAmountNodeName(String nodeName)
    {
        Integer amount;
        String name;
        String[] nodeNameParts = nodeName.split("\\Q" + separator + "\\E");
        
        if(nodeNameParts.length < 2)
            return null;
        
        name = nodeNameParts[nodeNameParts.length - 1];
        
        try
        { amount = Integer.parseInt(name); }
        catch(NumberFormatException exception)
        { return null; }
        
        return amount;
    }
    
    protected static Long getTimeFromNodeName(String nodeName)
    {
        Long time;
        String name;
        String[] nodeNameParts = nodeName.split("\\Q" + separator + "\\E");
        
        if(nodeNameParts.length < 2)
            return null;
        
        name = nodeNameParts[nodeNameParts.length - 1];
        
        try
        { time = Long.parseLong(name); }
        catch(NumberFormatException exception)
        { return null; }
        
        return time;
    }
    
    public void markPowerAsGranted(UUID playerId, int amount)
    {
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            int alreadyGranted = getPowerGrantedBy(playerId);
            
            powerGrants.put(playerId, alreadyGranted + amount);
        }
    }
    
    public void markPowerAsGranted(EntityPlayer player, int amount)
    { markPowerAsGranted(player.getGameProfile().getId(), amount); }
    
    public int getPowerGrantedBy(UUID playerId)
    {
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            Integer powerGranted = powerGrants.get(playerId);
            
            if(powerGranted == null)
                powerGranted = 0;
            
            QueuedRevocation queuedRevocation = revokeQueue.get(playerId);
            
            if(queuedRevocation != null)
                powerGranted -= queuedRevocation.getAmount();
            
            return powerGranted;
        }
    }
    
    public int getPowerGrantedBy(EntityPlayer player)
    { return getPowerGrantedBy(player.getGameProfile().getId()); }
    
    public int getPowerGrantedIncludingQueuedRevocationsBy(UUID playerId)
    {
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            Integer powerGranted = powerGrants.get(playerId);
            
            if(powerGranted == null)
                powerGranted = 0;
            
            return powerGranted;
        }
    }
    
    public int getPowerGrantedIncludingQueuedRevocationsBy(EntityPlayer player)
    { return getPowerGrantedIncludingQueuedRevocationsBy(player.getGameProfile().getId()); }
    
    public Map<UUID, Integer> getPowerGrants()
    {
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            Map<UUID, Integer> grants = new HashMap<UUID, Integer>(powerGrants);
            
            for(Entry<UUID, QueuedRevocation> entry : revokeQueue.entrySet())
            {
                Integer granted = grants.get(entry.getKey());
                
                if(granted == null)
                    continue;
                
                granted -= entry.getValue().getAmount();
                
                if(granted < 0)
                    granted = 0;
                
                grants.put(entry.getKey(), granted);
            }
            
            return grants;
        }
    }
    
    public Map<UUID, Integer> getPowerGrantsIncludingQueuedRevocations()
    {
        synchronized(powerGrants)
        { return new HashMap<UUID, Integer>(powerGrants); }
    }
    
    public int unmarkPowerAsGranted(UUID playerId, int amount) throws RevokingMorePowerThanAvailableException,
                                                                      RevokingMorePowerThanGrantedException
    {
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            int powerGranted = getPowerGrantedBy(playerId);
            
            if(amount > powerGranted)
                throw new RevokingMorePowerThanGrantedException(playerId, amount, powerGranted);
            
            int availablePower = getAvailablePower();
            
            if(amount > availablePower)
                throw new RevokingMorePowerThanAvailableException(availablePower, amount);
            
            if(powerGranted == amount)
                powerGrants.remove(playerId);
            else
                powerGrants.put(playerId, powerGranted - amount);
            
            return powerGranted;
        }
    }
    
    public int unmarkPowerAsGranted(EntityPlayer player, int amount) throws RevokingMorePowerThanAvailableException,
                                                                            RevokingMorePowerThanGrantedException
    { return unmarkPowerAsGranted(player.getGameProfile().getId(), amount); }
    
    public int forceUnmarkPowerAsGranted(UUID playerId, int amount) throws RevokingMorePowerThanGrantedException
    {
        int powerAvailable;
        int powerGranted;
        
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            powerAvailable = getAvailablePower();
            powerGranted = getPowerGrantedBy(playerId);
            
            if(powerGranted > amount)
                throw new RevokingMorePowerThanGrantedException(playerId, powerAvailable, amount);
            
            if(powerGranted == amount)
                powerGrants.remove(playerId);
            else
                powerGrants.put(playerId, powerGranted - amount);
        }
        
        if(amount > powerAvailable)
            onForceRevoke(playerId, amount, amount - powerAvailable);
        
        return powerGranted;
    }
    
    public int forceUnmarkPowerAsGranted(EntityPlayer player, int amount) throws RevokingMorePowerThanGrantedException
    { return forceUnmarkPowerAsGranted(player.getGameProfile().getId(), amount); }
    
    public int unmarkAllAvailablePowerAsGranted(UUID playerId)
    {
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            int availablePower = getAvailablePower();
            
            if(availablePower <= 0)
                return 0;
            
            int powerGranted = powerGrants.get(playerId);
            
            int toUnmark = powerGranted;
            
            if(availablePower < powerGranted)
                toUnmark = availablePower;
            
            if(powerGranted == toUnmark)
                powerGrants.remove(playerId);
            else
                powerGrants.put(playerId, powerGranted - toUnmark);
            
            return toUnmark;
        }
    }
    
    public int unmarkAllAvailablePowerAsGranted(EntityPlayer player)
    { return unmarkAllAvailablePowerAsGranted(player.getGameProfile().getId()); }
    
    public int getAmountQueuedForRevocation(UUID playerId)
    {
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            QueuedRevocation revocation = revokeQueue.get(playerId);
            
            if(revocation == null)
                return 0;
            
            return revocation.getAmount();
        }
    }
    
    public int getAmountQueuedForRevocation(EntityPlayer player)
    { return getAmountQueuedForRevocation(player.getGameProfile().getId()); }
    
    public int setAmountQueuedForRevocation(UUID playerId, int newAmount) throws RevokingMorePowerThanGrantedException
    {
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            QueuedRevocation revocation = revokeQueue.get(playerId);
            int amountGrantedTotal = getPowerGrantedIncludingQueuedRevocationsBy(playerId);
            
            if(amountGrantedTotal < newAmount)
            {
                if(revocation != null)
                    throw new RevokingMorePowerThanGrantedException(playerId, newAmount - revocation.getAmount(), amountGrantedTotal - revocation.getAmount());
                else
                    throw new RevokingMorePowerThanGrantedException(playerId, newAmount, amountGrantedTotal);
            }
            
            if(revocation == null)
            {
                revocation = new QueuedRevocation(playerId, new Date(new Date().getTime() + EnkiProtection.getInstance().getSettings().getNumberOfHoursUntilPowerRecovationExpires() * 3600000), newAmount);
                revokeQueue.put(playerId, revocation);
                return 0;
            }
            else
            {
                int old = revocation.getAmount();
                
                if(newAmount > revocation.getAmount())
                    revocation.setWhenToForceRevoke(new Date(new Date().getTime() + EnkiProtection.getInstance().getSettings().getNumberOfHoursUntilPowerRecovationExpires() * 3600000));
                
                revocation.setAmount(newAmount);
                return old;
            }
        }
    }
    
    public int setAmountQueuedForRevocation(EntityPlayer player, int newAmount) throws RevokingMorePowerThanGrantedException
    { return setAmountQueuedForRevocation(player.getGameProfile().getId(), newAmount); }
    
    public int addToAmountQueuedForRevocation(UUID playerId, int amountToAdd) throws RevokingMorePowerThanGrantedException
    {
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            int amountGrantedNotCurrentlyRevoking = getPowerGrantedBy(playerId);
            
            if(amountGrantedNotCurrentlyRevoking < amountToAdd)
                throw new RevokingMorePowerThanGrantedException(playerId, amountToAdd, amountGrantedNotCurrentlyRevoking);
            
            QueuedRevocation revocation = revokeQueue.get(playerId);
            
            if(revocation == null)
            {
                revocation = new QueuedRevocation(playerId, new Date(new Date().getTime() + EnkiProtection.getInstance().getSettings().getNumberOfHoursUntilPowerRecovationExpires() * 3600000), amountToAdd);
                revokeQueue.put(playerId, revocation);
                return 0;
            }
            else
            {
                int old = revocation.getAmount();
                
                revocation.setWhenToForceRevoke(new Date(new Date().getTime() + EnkiProtection.getInstance().getSettings().getNumberOfHoursUntilPowerRecovationExpires() * 3600000));
                
                revocation.addToAmount(amountToAdd);
                return old;
            }
        }
    }
    
    public int addToAmountQueuedForRevocation(EntityPlayer player, int amountToAdd) throws RevokingMorePowerThanGrantedException
    { return addToAmountQueuedForRevocation(player.getGameProfile().getId(), amountToAdd); }
    
    public int removeFromAmountQueuedForRevocation(UUID playerId, int amountToRemove) 
    {
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            QueuedRevocation revocation = revokeQueue.get(playerId);
            
            if(revocation == null)
                return 0;
            
            int old = revocation.getAmount();
            if(amountToRemove <= old)
            {
                powerGrants.remove(playerId);
                return old;
            }

            revocation.removeFromAmount(amountToRemove);
            return old;
        }
    }
    
    public int removeFromAmountQueuedForRevocation(EntityPlayer player, int amountToRemove)
    { return removeFromAmountQueuedForRevocation(player.getGameProfile().getId(), amountToRemove); }
    
    public int setAmountQueuedForRevocationToMax(UUID playerId)
    {
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            QueuedRevocation revocation = revokeQueue.get(playerId);
            int amountGrantedTotal = getPowerGrantedIncludingQueuedRevocationsBy(playerId);
            Date forceRevokeExpiry = new Date(new Date().getTime() + EnkiProtection.getInstance().getSettings().getNumberOfHoursUntilPowerRecovationExpires() * 3600000);
            
            if(revocation == null)
            {
                if(amountGrantedTotal <= 0)
                    return 0;
                else
                {
                    revocation = new QueuedRevocation(playerId, forceRevokeExpiry, amountGrantedTotal);
                    revokeQueue.put(playerId, revocation);
                    return amountGrantedTotal;
                }
            }
            
            if(amountGrantedTotal <= 0)
                throw new IllegalStateException("Player had power queued to revoke despite not having granted any.");
            
            if(amountGrantedTotal <= revocation.getAmount())
                throw new IllegalStateException("Player had more power queued to revoke than they had granted.");
            
            if(amountGrantedTotal == revocation.getAmount())
                return amountGrantedTotal;
            
            revocation.setAmount(amountGrantedTotal);
            revocation.setWhenToForceRevoke(forceRevokeExpiry);
            return amountGrantedTotal;
        }
    }
    
    public int setAmountQueuedForRevocationToMax(EntityPlayer player)
    { return setAmountQueuedForRevocationToMax(player.getGameProfile().getId()); }
    
    public int getTotalPower()
    {
        int power = 0;
        
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            for(Integer i : powerGrants.values())
                power += i;
        }
        
        return power;
    }
    
    public int getAvailablePower()
    {
        int power;
        
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            power = getTotalPower() - getPowerUsed();
            
            for(QueuedRevocation i : revokeQueue.values())
                power -= i.getAmount();
            
            if(power < 0)
                power = 0;
        }
        
        return power;
    }
    
    public int getAvailablePowerIgnoringQueuedRevocations()
    {
        synchronized(powerGrants)
        { return getTotalPower() - getPowerUsed(); }
    }
    
    public int getPowerPlayerHasGranted(UUID playerId)
    {
        Integer powerGranted;
        
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            powerGranted = powerGrants.get(playerId);
            
            if(powerGranted == null)
                powerGranted = 0;
        }
        
        return powerGranted;
    }
    
    public int getPowerPlayerHasGranted(EntityPlayer player)
    { return getPowerPlayerHasGranted(player.getGameProfile().getId()); }
    
    public void checkPowerRevocationQueue()
    {
        synchronized(powerGrants)
        {
            if(!isUsable)
                throw new ObjectFinishedWithException();
            
            Collection<QueuedRevocation> toRemove = new ArrayList<QueuedRevocation>();
            
            for(QueuedRevocation queuedRevocation : revokeQueue.values())
            {
                if(queuedRevocation.isDue())
                {
                    try
                    {
                        forceUnmarkPowerAsGranted(queuedRevocation.getPlayerId(), queuedRevocation.getAmount());
                        toRemove.add(queuedRevocation);
                    }
                    catch(RevokingMorePowerThanGrantedException ex)
                    { Logger.getLogger(ClaimPower.class.getName()).log(Level.SEVERE, null, ex); } // This should never happen ... famous last words.
                }
            }
            
            for(QueuedRevocation queuedRevocation : toRemove)
                revokeQueue.remove(queuedRevocation.getPlayerId());
        }
    }
    
    protected void onForceRevoke(UUID playerRevoking, int amountBeingRevoked, int amountMoreThanAvailablePowerBeingRevoked)
    {
        synchronized(forceRevokeListeners)
        {
            for(ForceRevokeListener listener : forceRevokeListeners)
                listener.onForceRevoke(playerRevoking, amountBeingRevoked, amountMoreThanAvailablePowerBeingRevoked);
        }
    }
    
    protected int getPowerUsed()
    {
        if(powerUsedGetter == null)
            return 0;
        
        return powerUsedGetter.getPowerUsed();
    }
    
    protected void notifyPlayerPowerOfPowerReturn(UUID playerId, int amount)
    {
        synchronized(queuedRevocationExpirationListeners)
        {
            for(QueuedRevocationExpirationListener listener : queuedRevocationExpirationListeners)
                listener.onQueuedRevocationExpiration(playerId, amount);
        }
    }
    
    void setPowerUsedGetter(PowerUsedGetter getter)
    { powerUsedGetter = getter; }
    
    void addListener(ForceRevokeListener listener)
    {
        synchronized(forceRevokeListeners)
        { forceRevokeListeners.add(listener); }
    }
    
    void addListener(QueuedRevocationExpirationListener listener)
    {
        synchronized(queuedRevocationExpirationListeners)
        { queuedRevocationExpirationListeners.add(listener); }
    }
    
    protected void cleanUp()
    {
        synchronized(powerGrants)
        {
            // Remove revocation queue.
            revokeQueue.clear();
            
            // Go through powerGrants, returning grants to players.
            for(Entry<UUID, Integer> entry : powerGrants.entrySet())
                notifyPlayerPowerOfPowerReturn(entry.getKey(), entry.getValue());
            
            powerGrants.clear();
            isUsable = false;
        }
    }
}