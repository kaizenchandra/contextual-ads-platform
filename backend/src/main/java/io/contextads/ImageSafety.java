package io.contextads;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

final class ImageSafety {
    private static final Semaphore DECODERS=new Semaphore(2);
    record Clean(byte[] png,int width,int height){}
    static Clean sanitize(byte[] input){
        if(input==null||input.length==0||input.length>2*1024*1024)throw new IllegalArgumentException("Image must be 1 byte to 2 MiB");
        boolean png=input.length>=8 && input[0]==(byte)137 && input[1]==80 && input[2]==78 && input[3]==71;
        boolean jpeg=input.length>=3 && input[0]==(byte)255 && input[1]==(byte)216 && input[2]==(byte)255;
        if(!png&&!jpeg)throw new IllegalArgumentException("Only PNG and JPEG image bytes are accepted");
        if(!DECODERS.tryAcquire())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"Image decoder capacity exhausted");
        try(var stream=new MemoryCacheImageInputStream(new ByteArrayInputStream(input))){
            var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new IllegalArgumentException("Invalid image");
            var reader=readers.next();
            try{
                reader.setInput(stream,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1||width>4096||height>4096||(long)width*height>8_000_000)throw new IllegalArgumentException("Image dimensions exceed 4096 per side / 8 megapixels");
                var decoded=reader.read(0);var clean=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
                var graphics=clean.createGraphics();try{graphics.drawImage(decoded,0,0,null);}finally{graphics.dispose();decoded.flush();}
                var output=new LimitedOutput();try{if(!ImageIO.write(clean,"png",output))throw new IllegalArgumentException("Cannot encode image");return new Clean(output.toByteArray(),width,height);}finally{clean.flush();}
            }finally{reader.dispose();}
        }catch(IOException error){throw new IllegalArgumentException("Image cannot be decoded safely");}
        finally{DECODERS.release();}
    }
    private static class LimitedOutput extends ByteArrayOutputStream {
        private void bound(int length){if((long)count+length>5*1024*1024)throw new IllegalArgumentException("Normalized image exceeds 5 MiB");}
        @Override public synchronized void write(int value){bound(1);super.write(value);}
        @Override public synchronized void write(byte[] bytes,int offset,int length){bound(length);super.write(bytes,offset,length);}
    }
}
