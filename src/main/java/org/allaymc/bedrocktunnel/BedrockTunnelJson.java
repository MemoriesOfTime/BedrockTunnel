package org.allaymc.bedrocktunnel;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.cloudburstmc.protocol.common.util.OptionalBoolean;

import java.io.IOException;

public final class BedrockTunnelJson {
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .addModule(new Jdk8Module())
            .addModule(new SimpleModule("bedrock-tunnel")
                    .addSerializer(ByteBuf.class, new ByteBufSerializer())
                    .addSerializer(OptionalBoolean.class, new OptionalBooleanSerializer()))
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private BedrockTunnelJson() {
    }

    private static final class ByteBufSerializer extends JsonSerializer<ByteBuf> {
        @Override
        public void serialize(ByteBuf value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            generator.writeString(ByteBufUtil.hexDump(value, value.readerIndex(), value.readableBytes()));
        }
    }

    private static final class OptionalBooleanSerializer extends JsonSerializer<OptionalBoolean> {
        @Override
        public void serialize(OptionalBoolean value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            if (value.isPresent()) {
                generator.writeBoolean(value.getAsBoolean());
            } else {
                generator.writeNull();
            }
        }
    }
}
