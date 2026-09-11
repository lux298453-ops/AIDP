package com.example.aidocumentplatform;

import com.example.aidocumentplatform.service.impl.FlinticPrototypeImageService;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FlinticPrototypeImageServiceTest {

    @Test
    void shouldBuildImageEndpointFromRelayRoot() {
        assertThat(FlinticPrototypeImageService.imagesEndpoint("https://api.flintic.uk"))
                .isEqualTo("https://api.flintic.uk/v1/images/generations");
    }

    @Test
    void shouldReplaceTextEndpointWithImageEndpoint() {
        assertThat(FlinticPrototypeImageService.imagesEndpoint("https://api.flintic.uk/v1/responses"))
                .isEqualTo("https://api.flintic.uk/v1/images/generations");
        assertThat(FlinticPrototypeImageService.imagesEndpoint("https://api.flintic.uk/v1/chat/completions"))
                .isEqualTo("https://api.flintic.uk/v1/images/generations");
    }

    @Test
    void shouldRemoveGeneratedChromaBackgroundAndKeepSubject() throws Exception {
        BufferedImage source = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        graphics.setColor(new Color(0, 255, 0));
        graphics.fillRect(0, 0, 20, 20);
        graphics.setColor(new Color(255, 190, 30));
        graphics.fillRect(6, 6, 8, 8);
        graphics.dispose();
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        ImageIO.write(source, "png", input);

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(
                FlinticPrototypeImageService.removeChromaBackground(input.toByteArray())));

        assertThat((result.getRGB(0, 0) >>> 24) & 0xff).isZero();
        assertThat((result.getRGB(10, 10) >>> 24) & 0xff).isEqualTo(255);
        assertThat(new Color(result.getRGB(10, 10), true).getRed()).isEqualTo(255);
    }

    @Test
    void shouldKeepOpaqueImageWhenChromaBackgroundCannotBeRemoved() throws Exception {
        BufferedImage source = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        graphics.setColor(new Color(36, 48, 72));
        graphics.fillRect(0, 0, 20, 20);
        graphics.setColor(new Color(255, 190, 30));
        graphics.fillRect(6, 6, 8, 8);
        graphics.dispose();
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        ImageIO.write(source, "png", input);
        byte[] original = input.toByteArray();

        byte[] result = FlinticPrototypeImageService.removeChromaBackgroundOrKeepOriginal(original);

        assertThat(result).isEqualTo(original);
        assertThat(ImageIO.read(new ByteArrayInputStream(result))).isNotNull();
    }
}
