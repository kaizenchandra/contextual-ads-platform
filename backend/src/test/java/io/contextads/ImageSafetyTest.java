package io.contextads;
import java.io.*;import java.awt.image.BufferedImage;import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class ImageSafetyTest {
 static byte[] png(int width,int height)throws Exception{var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return output.toByteArray();}
 @Test void rejectsExecutableFormatsAndOversizedInputs(){for(String bad:new String[]{"<svg><script>alert(1)</script></svg>","<html>ad</html>","GIF89a"})assertThrows(IllegalArgumentException.class,()->ImageSafety.sanitize(bad.getBytes()));assertThrows(IllegalArgumentException.class,()->ImageSafety.sanitize(new byte[2*1024*1024+1]));}
 @Test void stripsAppendedPayloadAndNormalizesPixels()throws Exception{byte[] input=png(16,8);var out=new ByteArrayOutputStream();out.write(input);out.write("<script>parent.attack()</script>".getBytes());var clean=ImageSafety.sanitize(out.toByteArray());assertEquals(16,clean.width());assertEquals(8,clean.height());assertFalse(new String(clean.png(),java.nio.charset.StandardCharsets.ISO_8859_1).contains("parent.attack"));assertNotNull(ImageIO.read(new ByteArrayInputStream(clean.png())));}
 @Test void rejectsDimensionBombBeforeFullDecode()throws Exception{byte[] input=png(4097,1);assertThrows(IllegalArgumentException.class,()->ImageSafety.sanitize(input));}
 @Test void jpegIsDecodedAndStoredAsFreshPng()throws Exception{var source=new BufferedImage(32,16,BufferedImage.TYPE_INT_RGB);var output=new ByteArrayOutputStream();assertTrue(ImageIO.write(source,"jpeg",output));var clean=ImageSafety.sanitize(output.toByteArray());assertEquals(32,clean.width());assertEquals(16,clean.height());assertEquals((byte)137,clean.png()[0]);assertNotNull(ImageIO.read(new ByteArrayInputStream(clean.png())));}
 @Test void rejectsTruncatedImage(){assertThrows(IllegalArgumentException.class,()->ImageSafety.sanitize(new byte[]{(byte)137,80,78,71,13,10,26,10}));}
}
