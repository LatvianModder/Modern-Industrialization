package aztech.modern_industrialization.pipes.api;

import aztech.modern_industrialization.util.NbtHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;

public class PipeNetworkManager {
    private Map<BlockPos, PipeNetwork> networkByBlock = new HashMap<>();
    private Map<BlockPos, Set<Direction>> links = new HashMap<>(); // TODO: (de)serialize
    private Set<PipeNetwork> networks = new HashSet<>();
    private int nextNetworkId = 0;
    private PipeNetworkType type;

    public PipeNetworkManager(PipeNetworkType type) {
        this.type = type;
    }

    /**
     * Add a network link and merge networks if necessary. Both the node at pos and the node at pos + direction must exist in the network.
     */
    public void addLink(BlockPos pos, Direction direction) {
        if(hasLink(pos, direction)) return;
        if(!canLink(pos, direction)) return;

        // Add links
        BlockPos otherPos = pos.offset(direction);
        links.get(pos).add(direction);
        links.get(otherPos).add(direction.getOpposite());

        // If the networks are different, we merge all nodes into `network`. We don't change other links.
        PipeNetwork network = networkByBlock.get(pos);
        PipeNetwork otherNetwork = networkByBlock.get(otherPos);
        if(network != otherNetwork) {
            for(Map.Entry<BlockPos, PipeNetworkNode> entry : otherNetwork.nodes.entrySet()) {
                PipeNetworkNode node = entry.getValue();
                BlockPos nodePos = entry.getKey();
                if(node != null) {
                    node.network = network;
                }
                networkByBlock.put(nodePos, network);
                network.nodes.put(nodePos, node);
            }
            networks.remove(otherNetwork);
        }
    }

    /**
     * Remove a network link and split networks if necessary. Both the node at pos and the node at pos + direction must exist in the network.
     */
    public void removeLink(BlockPos pos, Direction direction) {
        if(!hasLink(pos, direction)) return;

        // Remove links
        BlockPos otherPos = pos.offset(direction);
        links.get(pos).remove(direction);
        links.get(otherPos).remove(direction.getOpposite());

        // Run a DFS to mark all disconnected nodes.
        PipeNetwork network = networkByBlock.get(pos);
        Map<BlockPos, PipeNetworkNode> unvisitedNodes = new HashMap<>(network.nodes);

        class Dfs {
            private void dfs(BlockPos currentPos) {
                // warning: don't try to use the return value of Map#remove, because it might be null if the node is not loaded.
                if(!unvisitedNodes.containsKey(currentPos)) {
                    return;
                }
                unvisitedNodes.remove(currentPos);
                for(Direction direction : links.get(currentPos)) {
                    dfs(currentPos.offset(direction));
                }
            }
        }

        // Try to put all nodes in the current network
        Dfs dfs = new Dfs();
        dfs.dfs(pos);

        // If it was not possible, create a new network and transfer all unvisitedNodes to it.
        if(unvisitedNodes.size() > 0) {
            PipeNetwork newNetwork = createNetwork(network.data.clone());
            for(Map.Entry<BlockPos, PipeNetworkNode> entry : unvisitedNodes.entrySet()) {
                PipeNetworkNode node = entry.getValue();
                BlockPos nodePos = entry.getKey();
                if(node != null) {
                    node.network = newNetwork;
                }
                networkByBlock.put(nodePos, newNetwork);
                newNetwork.nodes.put(nodePos, node);
            }
        }
    }

    /**
     * Check if a link exists. A node must exist at pos.
     */
    public boolean hasLink(BlockPos pos, Direction direction) {
        return links.get(pos).contains(direction);
    }

    /**
     * Check if a link would be possible. A node must exist at pos.
     */
    public boolean canLink(BlockPos pos, Direction direction) {
        BlockPos otherPos = pos.offset(direction);
        PipeNetwork network = networkByBlock.get(pos);
        PipeNetwork otherNetwork = networkByBlock.get(otherPos);
        return otherNetwork != null && network.data.equals(otherNetwork.data);
    }

    /**
     * Add a node and create a new network for it.
     */
    public void addNode(PipeNetworkNode node, BlockPos pos, PipeNetworkData data) {
        if(networkByBlock.containsKey(pos)) throw new IllegalArgumentException("Cannot add a node that is already in the network.");

        PipeNetwork network = createNetwork(data.clone());
        if(node != null) {
            node.network = network;
        }
        networkByBlock.put(pos.toImmutable(), network);
        network.nodes.put(pos.toImmutable(), node);
        links.put(pos.toImmutable(), new HashSet<>());
    }

    /**
     * Remove a node and its network. Will remove all remaining links.
     */
    public void removeNode(BlockPos pos) {
        for(Direction direction : Direction.values()) {
            removeLink(pos, direction);
        }

        PipeNetwork network = networkByBlock.remove(pos);
        networks.remove(network);
        links.remove(pos);
    }

    /**
     * Should be called when a node is loaded, it will link the node to its network.
     */
    public void nodeLoaded(PipeNetworkNode node, BlockPos pos) {
        PipeNetwork network = networkByBlock.get(pos);
        node.network = network;
        network.nodes.put(pos.toImmutable(), node);
    }

    /**
     * Should be called when a node is unloaded, it will unlink the node from its network.
     */
    public void nodeUnloaded(PipeNetworkNode node, BlockPos pos) {
        node.network.nodes.put(pos.toImmutable(), null);
    }

    /**
     * Create a new empty network.
     */
    private PipeNetwork createNetwork(PipeNetworkData data) {
        PipeNetwork network = type.getNetworkCtor().apply(nextNetworkId, data);
        network.manager = this;
        nextNetworkId++;
        networks.add(network);
        return network;
    }

    /**
     * Mark the networks as unticked.
     */
    public void markNetworksAsUnticked() {
        for(PipeNetwork network : networks) {
            network.ticked = false;
        }
    }

    public void fromTag(CompoundTag tag) {
        // networks
        ListTag networksTag = tag.getList("networks", new CompoundTag().getType());
        for(Tag networkTag : networksTag) {
            PipeNetwork network = type.getNetworkCtor().apply(-1, null);
            network.manager = this;
            network.fromTag((CompoundTag) networkTag);
            networks.add(network);
        }

        // networkByBlock and links
        Map<Integer, PipeNetwork> networkIds = new HashMap<>();
        for(PipeNetwork network : networks) {
            networkIds.put(network.id, network);
        }
        int[] data = tag.getIntArray("networkByBlock");
        for(int i = 0; i < data.length/5; i++) {
            PipeNetwork network = networkIds.get(data[5*i+3]);
            BlockPos pos = new BlockPos(data[5*i], data[5*i+1], data[5*i+2]);
            networkByBlock.put(pos, network);
            network.nodes.put(pos, null);
            links.put(pos, new HashSet<Direction>(Arrays.asList(NbtHelper.decodeDirections((byte)data[5*i+4]))));
        }

        // nextNetworkId
        nextNetworkId = tag.getInt("nextNetworkId");
    }

    public CompoundTag toTag(CompoundTag tag) {
        // networks
        List<CompoundTag> networksTags = new ArrayList<>();
        for(PipeNetwork network : networks) {
            networksTags.add(network.toTag(new CompoundTag()));
        }
        ListTag networksTag = new ListTag();
        networksTag.addAll(networksTags);
        tag.put("networks", networksTag);

        // networkByBlock and links, every entry is identified by five consecutive integers: x, y, z, network id, encoded links
        int[] networkByBlockData = new int[networkByBlock.size() * 5];
        int i = 0;
        for(Map.Entry<BlockPos, PipeNetwork> entry : networkByBlock.entrySet()) {
            networkByBlockData[i++] = entry.getKey().getX();
            networkByBlockData[i++] = entry.getKey().getY();
            networkByBlockData[i++] = entry.getKey().getZ();
            networkByBlockData[i++] = entry.getValue().id;
            networkByBlockData[i++] = NbtHelper.encodeDirections(links.get(entry.getKey()));
        }
        tag.putIntArray("networkByBlock", networkByBlockData);

        // nextNetworkId
        tag.putInt("nextNetworkId", nextNetworkId);
        return tag;
    }

    public PipeNetworkType getType() {
        return type;
    }

    public Set<Direction> getNodeLinks(BlockPos pos) {
        return new HashSet<>(links.get(pos));
    }
}
