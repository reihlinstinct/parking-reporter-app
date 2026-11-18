package io.github.reihlinstinct.reporter.municipal;

import org.junit.Test;
import static org.junit.Assert.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.imageio.ImageIO;

public class ApiOutgoingPhotoTest {
    private static String hash(byte[] data) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) out.append(String.format("%02x", b & 255));
        return out.toString();
    }
    private static byte[] image(int width, int height, String format, boolean alpha) throws Exception {
        BufferedImage source = new BufferedImage(width,height,alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = source.createGraphics();
        if (!alpha) {
            g.setColor(Color.RED); g.fillRect(0,0,width/2,height/2);
            g.setColor(Color.GREEN); g.fillRect(width/2,0,width-width/2,height/2);
            g.setColor(Color.BLUE); g.fillRect(0,height/2,width/2,height-height/2);
            g.setColor(Color.YELLOW); g.fillRect(width/2,height/2,width-width/2,height-height/2);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source,format,out)); source.flush(); return out.toByteArray();
    }
    private static byte[] exif(byte[] jpeg, int orientation, boolean little) throws Exception {
        byte[] tiff = new byte[]{'I','I',42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,(byte)orientation,0,0,0,0,0,0,0};
        if (!little) tiff = new byte[]{'M','M',0,42,0,0,0,8,0,1,1,18,0,3,0,0,0,1,0,(byte)orientation,0,0,0,0,0,0};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg,0,2); out.write(new byte[]{(byte)255,(byte)225,0,34,'E','x','i','f',0,0});
        out.write(tiff); out.write(jpeg,2,jpeg.length-2); return out.toByteArray();
    }
    private static void near(int expected, int observed) {
        Color a=new Color(expected), b=new Color(observed);
        assertTrue(Math.abs(a.getRed()-b.getRed())<30 && Math.abs(a.getGreen()-b.getGreen())<30 && Math.abs(a.getBlue()-b.getBlue())<30);
    }
    @Test public void cameraOriginalIsSampledBeforeDecodeAndHashesBindBothVersions() throws Exception {
        byte[] original=image(5800,4200,"jpeg",false), snapshot=original.clone();
        ApiOutgoingPhoto.Prepared photo=ApiOutgoingPhoto.prepare(original,hash(original));
        assertTrue(photo.width<=2560 && photo.height<=2560 && (long)photo.width*photo.height<=4000000);
        assertTrue(photo.jpeg().length<=1100000);
        assertEquals(hash(original),photo.originalHash); assertEquals(hash(photo.jpeg()),photo.outgoingHash);
        assertArrayEquals(snapshot,original); assertNotEquals(photo.originalHash,photo.outgoingHash);
        byte[] mutable=photo.jpeg(); mutable[0]=0; assertEquals((byte)255,photo.jpeg()[0]);
        assertEquals("PreparedPhoto(redacted)",photo.toString());
    }
    @Test public void allExifOrientationsBothByteOrdersAreAppliedAndRemoved() throws Exception {
        // Expected output corner order TL, TR, BL, BR, independent of transform implementation.
        int r=Color.RED.getRGB(),g=Color.GREEN.getRGB(),b=Color.BLUE.getRGB(),y=Color.YELLOW.getRGB();
        int[][] colors={{r,g,b,y},{g,r,y,b},{y,b,g,r},{b,y,r,g},{r,b,g,y},{b,r,y,g},{y,g,b,r},{g,y,r,b}};
        for (boolean little : new boolean[]{true,false}) for(int orientation=1;orientation<=8;orientation++) {
            byte[] original=exif(image(80,40,"jpeg",false),orientation,little);
            ApiOutgoingPhoto.Prepared photo=ApiOutgoingPhoto.prepare(original,hash(original));
            assertEquals(orientation>=5?40:80,photo.width); assertEquals(orientation>=5?80:40,photo.height);
            BufferedImage result=ImageIO.read(new ByteArrayInputStream(photo.jpeg()));
            int w=result.getWidth(),h=result.getHeight(); int[] expected=colors[orientation-1];
            near(expected[0],result.getRGB(w/4,h/4)); near(expected[1],result.getRGB(3*w/4,h/4));
            near(expected[2],result.getRGB(w/4,3*h/4)); near(expected[3],result.getRGB(3*w/4,3*h/4));
            String encoded=new String(photo.jpeg(),java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(encoded.contains("Exif")); result.flush();
        }
    }
    @Test public void pngTransparencyUsesExplicitWhiteMatteAndJpegMime() throws Exception {
        byte[] input=image(40,40,"png",true);
        ApiOutgoingPhoto.Prepared photo=ApiOutgoingPhoto.prepare(input,hash(input));
        assertEquals((byte)255,photo.jpeg()[0]); assertEquals((byte)216,photo.jpeg()[1]);
        near(Color.WHITE.getRGB(),ImageIO.read(new ByteArrayInputStream(photo.jpeg())).getRGB(20,20));
    }
    @Test public void mismatchCorruptionOversizeAndInvalidExifRefuse() throws Exception {
        byte[] valid=image(40,40,"jpeg",false);
        assertThrows(IllegalArgumentException.class,()->ApiOutgoingPhoto.prepare(valid,"synthetic-not-a-hash"));
        for(byte[] input:new byte[][]{new byte[0],new byte[]{1,2,3},new byte[ApiOutgoingPhoto.MAX_INPUT+1],
                exif(valid,0,true),exif(valid,9,true),Arrays.copyOf(valid,valid.length/2)}) {
            assertThrows(Exception.class,()->ApiOutgoingPhoto.prepare(input,hash(input)));
        }
    }
    @Test public void pngExifOrientationAndCrcAreVerified() throws Exception {
        byte[] png=image(80,40,"png",false);
        byte[] wrapped=exif(image(2,2,"jpeg",false),6,true);
        byte[] tiff=Arrays.copyOfRange(wrapped,12,38);
        ByteArrayOutputStream chunk=new ByteArrayOutputStream();
        java.io.DataOutputStream data=new java.io.DataOutputStream(chunk);
        data.writeInt(tiff.length); data.writeBytes("eXIf"); data.write(tiff);
        java.util.zip.CRC32 crc=new java.util.zip.CRC32();
        byte[] before=chunk.toByteArray(); crc.update(before,4,before.length-4); data.writeInt((int)crc.getValue());
        ByteArrayOutputStream combined=new ByteArrayOutputStream();
        combined.write(png,0,33); combined.write(chunk.toByteArray()); combined.write(png,33,png.length-33);
        byte[] input=combined.toByteArray();
        ApiOutgoingPhoto.Prepared photo=ApiOutgoingPhoto.prepare(input,hash(input));
        assertEquals(40,photo.width); assertEquals(80,photo.height);
        near(Color.BLUE.getRGB(),ImageIO.read(new ByteArrayInputStream(photo.jpeg())).getRGB(10,20));
        input[33+chunk.size()-1]^=1;
        assertThrows(IllegalArgumentException.class,()->ApiOutgoingPhoto.prepare(input,hash(input)));
    }
    @Test public void highEntropyAboveByteCapRefusesRatherThanReducingBelowQualityFloor() throws Exception {
        BufferedImage source=new BufferedImage(2500,1500,BufferedImage.TYPE_INT_RGB);
        java.util.Random random=new java.util.Random(0);
        for(int y=0;y<1500;y++) for(int x=0;x<2500;x++) source.setRGB(x,y,random.nextInt(0x1000000));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); ImageIO.write(source,"png",bytes); source.flush();
        byte[] original=bytes.toByteArray();
        assertThrows(IllegalArgumentException.class,()->ApiOutgoingPhoto.prepare(original,hash(original)));
    }
    @Test public void dimensionsOverTwentyThousandRefuseBeforeAllocation() throws Exception {
        byte[] png=image(1,1,"png",false);
        java.nio.ByteBuffer.wrap(png).putInt(16,20001).putInt(20,20001);
        java.util.zip.CRC32 crc=new java.util.zip.CRC32(); crc.update(png,12,17);
        java.nio.ByteBuffer.wrap(png).putInt(29,(int)crc.getValue());
        assertThrows(IllegalArgumentException.class,()->ApiOutgoingPhoto.prepare(png,hash(png)));
    }
}
