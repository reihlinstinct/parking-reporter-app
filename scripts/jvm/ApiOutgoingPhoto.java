package io.github.reihlinstinct.reporter.municipal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Inactive JVM preparation only. No file rewrite, network, credentials or adapter caller.
 * Review and approve the exact derivative separately before any submission.
 * Policy aligns with Android OutgoingEvidence principles, not byte-identical encoders.
 */
public final class ApiOutgoingPhoto {
    public static final int MAX_INPUT = 25 * 1024 * 1024;
    public static final int MAX_EDGE = 20000;
    public static final int OUT_EDGE = 2560;
    public static final int OUT_PIXELS = 4000000;
    public static final int MAX_JPEG = 1100000;

    public static final class Prepared {
        private final byte[] jpeg;
        public final String originalHash, outgoingHash;
        public final int width, height, orientation;
        private Prepared(byte[] jpeg, String originalHash, int width, int height, int orientation) throws Exception {
            this.jpeg = jpeg.clone(); this.originalHash = originalHash;
            this.outgoingHash = hash(jpeg); this.width = width; this.height = height;
            this.orientation = orientation;
        }
        public byte[] jpeg() { return jpeg.clone(); }
        @Override public String toString() { return "PreparedPhoto(redacted)"; }
    }

    private static void need(boolean valid) {
        if (!valid) throw new IllegalArgumentException("API_PHOTO_PREPARATION_REFUSED");
    }
    private static String hash(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder text = new StringBuilder();
        for (byte b : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }

    public static Prepared prepare(byte[] original, String approvedOriginalHash) throws Exception {
        need(original != null && original.length > 0 && original.length <= MAX_INPUT);
        String originalHash = hash(original);
        need(originalHash.equals(approvedOriginalHash));
        int orientation = orientation(original);
        BufferedImage decoded;
        try (ImageInputStream input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
            need(input != null);
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            need(readers.hasNext());
            ImageReader reader = readers.next();
            try {
                reader.addIIOReadWarningListener((source, warning) -> {
                    throw new IllegalArgumentException("API_PHOTO_DECODE_WARNING_REFUSED");
                });
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                need(format.equalsIgnoreCase("jpeg") || format.equalsIgnoreCase("png"));
                int width = reader.getWidth(0), height = reader.getHeight(0);
                need(width > 0 && height > 0 && width <= MAX_EDGE && height <= MAX_EDGE);
                int sample = 1;
                while ((width + sample - 1) / sample > OUT_EDGE || (height + sample - 1) / sample > OUT_EDGE ||
                        (long)((width + sample - 1) / sample) * ((height + sample - 1) / sample) > OUT_PIXELS) sample *= 2;
                ImageReadParam params = reader.getDefaultReadParam();
                params.setSourceSubsampling(sample, sample, 0, 0);
                decoded = reader.read(0, params);
                need(decoded != null && decoded.getWidth() == (width + sample - 1) / sample &&
                        decoded.getHeight() == (height + sample - 1) / sample);
            } finally { reader.dispose(); }
        }
        try {
            int w = decoded.getWidth(), h = decoded.getHeight();
            boolean swap = orientation >= 5;
            BufferedImage result = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
            try {
                // Transparent input has an explicit white matte, never an encoder-dependent background.
                Graphics2D graphics = result.createGraphics();
                graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, result.getWidth(), result.getHeight());
                graphics.dispose();
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int tx = x, ty = y;
                    switch (orientation) {
                        case 2: tx = w - 1 - x; break;
                        case 3: tx = w - 1 - x; ty = h - 1 - y; break;
                        case 4: ty = h - 1 - y; break;
                        case 5: tx = y; ty = x; break;
                        case 6: tx = h - 1 - y; ty = x; break;
                        case 7: tx = h - 1 - y; ty = w - 1 - x; break;
                        case 8: tx = y; ty = w - 1 - x; break;
                        default: break;
                    }
                    int argb = decoded.getRGB(x, y), alpha = (argb >>> 24) & 255;
                    int r = (((argb >>> 16) & 255) * alpha + 255 * (255 - alpha) + 127) / 255;
                    int g = (((argb >>> 8) & 255) * alpha + 255 * (255 - alpha) + 127) / 255;
                    int b = ((argb & 255) * alpha + 255 * (255 - alpha) + 127) / 255;
                    result.setRGB(tx, ty, (r << 16) | (g << 8) | b);
                }
                for (int quality : new int[]{90, 80, 70, 60}) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                    try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
                        writer.setOutput(output);
                        ImageWriteParam param = writer.getDefaultWriteParam();
                        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        param.setCompressionQuality(quality / 100f);
                        writer.write(null, new IIOImage(result, null, null), param);
                        output.flush();
                    } finally { writer.dispose(); }
                    if (bytes.size() <= MAX_JPEG) return new Prepared(bytes.toByteArray(), originalHash,
                            result.getWidth(), result.getHeight(), orientation);
                }
                throw new IllegalArgumentException("API_PHOTO_REVIEW_LOWER_QUALITY_REFUSED");
            } finally { result.flush(); }
        } finally { decoded.flush(); }
    }

    private static int u16(byte[] b, int p, boolean le) {
        need(p >= 0 && p + 2 <= b.length);
        return le ? (b[p] & 255) | ((b[p+1] & 255) << 8) : ((b[p] & 255) << 8) | (b[p+1] & 255);
    }
    private static long u32(byte[] b, int p, boolean le) {
        return le ? (long)u16(b,p,true) | ((long)u16(b,p+2,true) << 16) :
                ((long)u16(b,p,false) << 16) | u16(b,p+2,false);
    }
    private static int tiff(byte[] bytes, int start, int end) {
        need(start >= 0 && end <= bytes.length && end - start >= 8);
        boolean le = bytes[start] == 'I' && bytes[start+1] == 'I';
        need(le || (bytes[start] == 'M' && bytes[start+1] == 'M'));
        need(u16(bytes,start+2,le) == 42);
        long offset = u32(bytes,start+4,le);
        need(offset >= 8 && offset <= end - start - 2);
        int ifd = start + (int)offset, count = u16(bytes,ifd,le);
        need((long)ifd + 2 + 12L * count + 4 <= end);
        int orientation = 1; boolean found = false;
        for (int i=0; i<count; i++) {
            int entry=ifd+2+12*i;
            if (u16(bytes,entry,le) == 0x112) {
                need(!found && u16(bytes,entry+2,le) == 3 && u32(bytes,entry+4,le) == 1);
                orientation=u16(bytes,entry+8,le); need(orientation >= 1 && orientation <= 8); found=true;
            }
        }
        return orientation;
    }
    private static int orientation(byte[] bytes) {
        int orientation=1; boolean found=false;
        if (bytes.length >= 2 && (bytes[0]&255)==255 && (bytes[1]&255)==216) {
            int p=2;
            while (p<bytes.length) {
                need((bytes[p++]&255)==255);
                while (p<bytes.length && (bytes[p]&255)==255) p++;
                need(p<bytes.length); int marker=bytes[p++]&255;
                if (marker==218 || marker==217) break;
                need(marker!=0 && marker!=216 && !(marker>=208 && marker<=215));
                int size=u16(bytes,p,false); need(size>=2 && p+size<=bytes.length);
                if (marker==225 && size>=8 && bytes[p+2]=='E' && bytes[p+3]=='x' && bytes[p+4]=='i' &&
                        bytes[p+5]=='f' && bytes[p+6]==0 && bytes[p+7]==0) {
                    need(!found); orientation=tiff(bytes,p+8,p+size); found=true;
                }
                p+=size;
            }
        } else if (bytes.length>=8 && bytes[0]==(byte)137 && bytes[1]=='P' && bytes[2]=='N' && bytes[3]=='G') {
            int p=8;
            while (p<bytes.length) {
                need(p+12<=bytes.length); long size=u32(bytes,p,false);
                need(size<=bytes.length-p-12);
                if (bytes[p+4]=='e' && bytes[p+5]=='X' && bytes[p+6]=='I' && bytes[p+7]=='f') {
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(bytes,p+4,4+(int)size);
                    need(crc.getValue() == u32(bytes,p+8+(int)size,false));
                    need(!found); orientation=tiff(bytes,p+8,p+8+(int)size); found=true;
                }
                p+=12+(int)size;
            }
        }
        return orientation;
    }
    private ApiOutgoingPhoto() {}
}
