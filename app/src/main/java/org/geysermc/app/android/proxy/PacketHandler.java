package org.geysermc.app.android.proxy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory;
import com.nukkitx.network.util.DisconnectReason;
import com.nukkitx.protocol.bedrock.BedrockServerSession;
import com.nukkitx.protocol.bedrock.handler.BedrockPacketHandler;
import com.nukkitx.protocol.bedrock.packet.LoginPacket;
import com.nukkitx.protocol.bedrock.packet.PlayStatusPacket;
import com.nukkitx.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import com.nukkitx.protocol.bedrock.packet.ResourcePackStackPacket;
import com.nukkitx.protocol.bedrock.packet.ResourcePacksInfoPacket;
import com.nukkitx.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import com.nukkitx.protocol.bedrock.util.EncryptionUtils;

import java.io.IOException;
import java.security.interfaces.ECPublicKey;

public class PacketHandler implements BedrockPacketHandler {

    private BedrockServerSession session;
    private ProxyServer masterServer;

    private Player player;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);;

    public PacketHandler(BedrockServerSession session, ProxyServer masterServer) {
        this.session = session;
        this.masterServer = masterServer;

        session.addDisconnectHandler((reason) -> disconnect(reason));
    }

    public void disconnect(DisconnectReason reason) {
        if (player != null) {
            masterServer.getLogger().info(player.getDisplayName() + " has disconnected from the master server (" + reason + ")");
            masterServer.getPlayers().remove(player.getXuid());
        }
    }

    @Override
    public boolean handle(LoginPacket packet) {
        // Check the protocol version is correct
        int protocol = packet.getProtocolVersion();
        if (protocol != ProxyServer.CODEC.getProtocolVersion()) {
            PlayStatusPacket status = new PlayStatusPacket();
            if (protocol > ProxyServer.CODEC.getProtocolVersion()) {
                status.setStatus(PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD);
            } else {
                status.setStatus(PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD);
            }
            session.sendPacket(status);
        }

        // Set the session codec
        session.setPacketCodec(ProxyServer.CODEC);

        // Read the raw chain data
        JsonNode rawChainData;
        try {
            rawChainData = OBJECT_MAPPER.readTree(packet.getChainData().toByteArray());
        } catch (IOException e) {
            throw new AssertionError("Unable to read chain data!");
        }

        // Get the parsed chain data
        JsonNode chainData = rawChainData.get("chain");
        if (chainData.getNodeType() != JsonNodeType.ARRAY) {
            throw new AssertionError("Invalid chain data!");
        }

        try {
            // Parse the signed jws object
            JWSObject jwsObject;
            jwsObject = JWSObject.parse(chainData.get(chainData.size() - 1).asText());

            // Read the JWS payload
            JsonNode payload = OBJECT_MAPPER.readTree(jwsObject.getPayload().toBytes());

            // Check the identityPublicKey is there
            if (payload.get("identityPublicKey").getNodeType() != JsonNodeType.STRING) {
                throw new AssertionError("Missing identity public key!");
            }

            // Create an ECPublicKey from the identityPublicKey
            ECPublicKey identityPublicKey = EncryptionUtils.generateKey(payload.get("identityPublicKey").textValue());

            // Get the skin data to validate the JWS token
            JWSObject skinData = JWSObject.parse(packet.getSkinData().toString());
            if (skinData.verify(new DefaultJWSVerifierFactory().createJWSVerifier(skinData.getHeader(), identityPublicKey))) {
                // Make sure the client sent over the username, xuid and other info
                if (payload.get("extraData").getNodeType() != JsonNodeType.OBJECT) {
                    throw new AssertionError("Missing client data");
                }

                // Fetch the client data
                JsonNode extraData = payload.get("extraData");

                // Create a new player and add it to the players list
                player = new Player(extraData, session);
                masterServer.getPlayers().put(player.getXuid(), player);

                // Tell the client we have logged in successfully
                PlayStatusPacket playStatusPacket = new PlayStatusPacket();
                playStatusPacket.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
                session.sendPacket(playStatusPacket);

                // Tell the client there are no resourcepacks
                ResourcePacksInfoPacket resourcePacksInfo = new ResourcePacksInfoPacket();
                session.sendPacket(resourcePacksInfo);
            } else {
                throw new AssertionError("Invalid identity public key!");
            }
        } catch (Exception e) {
            // Disconnect the client
            session.disconnect("disconnectionScreen.internalError.cantConnect");
            throw new AssertionError("Failed to login", e);
        }

        return false;
    }

    @Override
    public boolean handle(ResourcePackClientResponsePacket packet) {
        switch (packet.getStatus()) {
            case COMPLETED:
                masterServer.getLogger().info("Logged in " + player.getDisplayName() + " (" + player.getXuid() + ", " + player.getIdentity() + ")");
                player.sendStartGame();
                break;
            case HAVE_ALL_PACKS:
                ResourcePackStackPacket stack = new ResourcePackStackPacket();
                stack.setExperimental(false);
                stack.setForcedToAccept(false);
                stack.setGameVersion("*");
                session.sendPacket(stack);
                break;
            default:
                session.disconnect("disconnectionScreen.resourcePack");
                break;
        }

        return true;
    }

    @Override
    public boolean handle(SetLocalPlayerAsInitializedPacket packet) {
        masterServer.getLogger().debug("Player initialized: " + player.getDisplayName());

        player.connectToServer(ProxyServer.getInstance().getAddress(), ProxyServer.getInstance().getPort());

        return false;
    }
}
