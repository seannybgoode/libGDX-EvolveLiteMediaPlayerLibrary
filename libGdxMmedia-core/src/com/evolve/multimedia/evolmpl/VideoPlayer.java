package com.evolve.multimedia.evolmpl;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.evolve.datastruct.Queue;
import com.xuggle.mediatool.IMediaReader;
import com.xuggle.mediatool.ToolFactory;
import com.xuggle.xuggler.IAudioSamples;
import com.xuggle.xuggler.ICodec;
import com.xuggle.xuggler.IContainer;
import com.xuggle.xuggler.IPacket;
import com.xuggle.xuggler.IStream;
import com.xuggle.xuggler.IStreamCoder;
import com.xuggle.xuggler.IVideoPicture;
import com.xuggle.xuggler.IVideoResampler;
import com.xuggle.xuggler.video.ConverterFactory;
import com.xuggle.xuggler.video.IConverter;

/**
 * A VideoTextureProvider wraps a video on the internal file system.
 * It provides methods to play, pause and stop the associated video
 * and provides a Texture object that contains the current video
 * frame
 * @author ajs
 * 
 * April 8th 2015 - Audio support added by @author Sean Brophy
 * courtesy of Evolve Interactive Inc.
 * www.evolveinteractive.ca
 */
public class VideoPlayer {
	
	public enum PlayState {
		PLAYING,
		PAUSED,
		STOPPED
	}

	private String videoPath;
	private FileHandle fileHandle;
	private InputStream inputStream;
	
	private Texture texture;
	
	private PlayState playState = PlayState.STOPPED;
	private long playTimeMilliseconds = 0;
	public long getPlayTimeMilliseconds() {
		return playTimeMilliseconds;
	}
	
	private long avSyncValue = 0;

	private IContainer container;
	
	
	// The tollerance used when waiting for the playhead to catch up
	private static final long SYNC_TOLERANCE_MICROSECONDS = 1000;
	//used to tell if audio is finished (if there are no threads writing out audio data)
	
	private static SourceDataLine mLine;
	
	private int videoStreamId = -1;
	private IStreamCoder videoCoder = null;
	
	//the audio stream from the codec
	private int audioStreamId = -1;
	private IStreamCoder audioCoder = null;
	
	private ExecutorService writeOutPool;
	
	private VideoScreen screen;
	private long videoTimeStamp;
	private long audioTimeStamp;
	
	private Thread packetHandlerThread;
	private PacketHandler packetHandlerRunnable;
	public boolean videoComplete = false;
	private boolean playbackComplete = false;
	private VideoFrame newVideoFrame;
	private int videoFrame;
	
	
	/**
	 * ctor
	 * @param _videoPath An internal LibGDX path to a video file
	 * @param videoScreenInstance the instance of the libGDX video screen
	 */
	public VideoPlayer(String _videoPath, VideoScreen videoScreenInstance) {
		this.screen = videoScreenInstance;
		videoPath = _videoPath;
		writeOutPool = Executors.newSingleThreadExecutor();
		// Let's make sure that we can actually convert video pixel formats.
		if (!IVideoResampler.isSupported(IVideoResampler.Feature.FEATURE_COLORSPACECONVERSION)) {
			throw new RuntimeException("VideoTextureProvider requires the GPL version of Xuggler (with IVideoResampler support)");
		}
		
		// Get a handle to the file and open it for reading
		fileHandle = Gdx.files.internal(videoPath);
		
		if(!fileHandle.exists()) {
			throw new IllegalArgumentException("Video file does not exist: " + videoPath);
		}
		
		inputStream = fileHandle.read();
		
		// Initialize the texture to a black color until the video is ready
		texture = new Texture(Gdx.files.internal("mov/black.png"));
		setDefaultTexture();
	}

	/**
	 * Plays the video stream, or resumes it if it was paused
	 */
	@SuppressWarnings("deprecation")
	public void play() {
		
		if(container == null) {
			// Create a Xuggler container object
			container = IContainer.make();
		}
		
		if(!container.isOpened()) {
			// Open up the container
			if (container.open(inputStream, null) < 0) {
				throw new RuntimeException("Could not open video file: " + videoPath);
			}
	
			// Query how many streams the call to open found
			int numStreams = container.getNumStreams();
	
			// Iterate through the streams to find the first video stream
			for (int i = 0; i < numStreams; i++) {
				// Find the stream object
				IStream stream = container.getStream(i);
				
				// Get the pre-configured decoder that can decode this stream;
				IStreamCoder coder = stream.getStreamCoder();
				
				if (coder.getCodecType() == ICodec.Type.CODEC_TYPE_VIDEO) {
					videoStreamId = i;
					videoCoder = coder;
					break;
				}
			}
			// now find the audio stream
			for (int i = 0; i < numStreams; i++) {
				// Find the stream object
				IStream stream = container.getStream(i);
				
				// Get the pre-configured decoder that can decode this stream;
				IStreamCoder coder = stream.getStreamCoder();
				
				if (coder.getCodecType() == ICodec.Type.CODEC_TYPE_AUDIO) {
					audioStreamId = i;
					audioCoder = coder;
					break;
				}
			}
			
			if (videoStreamId == -1) {
				throw new RuntimeException("Could not find video stream in container: " + videoPath);
			}
			
			if (audioStreamId == -1) {
				throw new RuntimeException("Could not find audio stream in container: " + videoPath);
			}
			
			if(audioCoder.open() < 0)
			{
				throw new RuntimeException("Could not open audio decoder for container: " + videoPath);
			}
			else
			{
				//now prep the audio stream
				openJavaSound(audioCoder);
			}
			/* Now we have found the video stream in this file. Let's open up our
			 * decoder so it can do work
			 */
			if (videoCoder.open() < 0) {
				throw new RuntimeException("Could not open video decoder for container: " + videoPath);
			}
		}
		//samples.setTimeStamp(picture.getTimeStamp());
		this.packetHandlerRunnable = new PacketHandler(container);
		this.packetHandlerThread = new Thread(packetHandlerRunnable);
		this.packetHandlerThread.start();
		playState = PlayState.PLAYING;
		Gdx.app.log("Status","Video Player Playing");
	}
	
	/**
	 * Pauses the video stream, allowing it to be resumed later
	 * with play()
	 */
	public void pause() {
		playState = PlayState.PAUSED;
		System.out.println("Video Player Paused");
	}
	
	/**
	 * Stops the video stream, resetting the play head to the
	 * beginning of the stream
	 */
	public void stop() {
		if(container != null)
		{
			container.close();
		}
		container = null;
		
		try {
			inputStream.close();
		} catch (IOException e) {}
		inputStream = fileHandle.read();
		
		playTimeMilliseconds = 0;
		
		// Initialize the texture to a black color until it is next played
		
		setDefaultTexture();
		
		playState = PlayState.STOPPED;
		Gdx.app.log("Status","Video Player Stopped");
	}
	
	/** Sets the screen to a default black texture*/
	private void setDefaultTexture()
	{
		texture = new Texture(new FileHandle("mov/black.png"));
		if(screen.sprite == null) {
			// Initialize the sprite
			screen.sprite = new Sprite(texture);
			
			screen.sprite.setSize(1.0f, 0.5f * screen.sprite.getHeight() / screen.sprite.getWidth());
			screen.sprite.setOrigin(screen.sprite.getWidth()/2, screen.sprite.getHeight()/2);
			screen.sprite.setPosition(-screen.sprite.getWidth()/2, -screen.sprite.getHeight()/2);
		}
		else
		{
			screen.sprite.setTexture(texture);
		}
	}
	
	public PlayState getState() {
		return playState;
	}
	
	/**
	 * Empties the audio queue into the sound device
	 * and grabs a video frame for playback.
	 * If the video is behind, grabs a frame that's further ahead
	 */
	public void update(float dtSeconds) {
		if(playState != PlayState.PLAYING) return;
			
		this.playTimeMilliseconds += dtSeconds*1000;
		
		//if we haven't stashed a video frame to be played later, get a new frame
		if(newVideoFrame == null)
		{
			this.newVideoFrame = this.packetHandlerRunnable.getVideoFrame();
			if(newVideoFrame != null)
			{
				updateTexture(newVideoFrame.image);	
				videoTimeStamp = newVideoFrame.timeStamp;
				//this is for debugging
				this.avSyncValue = Math.abs(videoTimeStamp - audioTimeStamp);
			}
			newVideoFrame = null;
			
		}	

		/**write out all the audio that you can, we do this so avoid breaks in the sound output
		that present as skips, crackles and pops - note that packet decoder is decoding audio even as 
		this code runs**/
		AudioFrame newAudioFrame = this.packetHandlerRunnable.getAudioFrame(); 
		while(newAudioFrame!= null)
		{
			this.writeOutPool.execute(new WriteOutSoundBytes(newAudioFrame.byteArray, newAudioFrame.timeStamp));
			newAudioFrame = this.packetHandlerRunnable.getAudioFrame();
		}
		
		
		//this block was causing sync problems
		if(newVideoFrame != null)
			videoTimeStamp = newVideoFrame.timeStamp;

		
	
		//detect if playback has completed
		if(this.packetHandlerRunnable.getNumAudioPackets() <= 0 
				&& this.packetHandlerRunnable.getNumVideoPackets() <= 0 
				&& this.packetHandlerRunnable.isDecoderComplete())
		{
			synchronized(packetHandlerRunnable)
			{
				this.packetHandlerRunnable.notify();
				this.playbackComplete = true;
			}
		}
		if(videoComplete)
			stop();

	}
	
	public boolean isPlaybackComplete() {
		return playbackComplete;
	}

	public long getAudioTimeStamp() {
		return audioTimeStamp;
	}

	public long getAvSyncValue() {
		return avSyncValue;
	}

	public long getVideoTimeStamp() {
		return videoTimeStamp;
	}


	/**
	 * Updates the internal texture with new video data
	 * @param img The new video frame data
	 */
	private void updateTexture(BufferedImage img) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		
		try {
			ImageIO.write(img, "bmp", baos);
			byte[] bytes = baos.toByteArray();
			Pixmap pix = new Pixmap(bytes, 0, bytes.length);
			screen.setPixmap(pix);
			this.videoFrame ++;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}

	/**
	 * Gets the video texture
	 * @return The video texture, containing the current video frame
	 */
	public Texture getTexture() {
		return texture;
	}
	
	/*
	 * We use Java sound because libGDX does not offer low-level enough functionality
	 * to write through the libgDX sound system.
	 */
	private static void openJavaSound(IStreamCoder aAudioCoder)
	{
	    AudioFormat audioFormat = new AudioFormat(aAudioCoder.getSampleRate(),
	        (int)IAudioSamples.findSampleBitDepth(aAudioCoder.getSampleFormat()),
	        aAudioCoder.getChannels(),
	        true, /* xuggler defaults to signed 16 bit samples */
	        false);
	    DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
	    try
	    {
	      mLine = (SourceDataLine) AudioSystem.getLine(info);
	      /**
	       * if that succeeded, try opening the line.
	       */
	      mLine.open(audioFormat);
	      /**
	       * And if that succeed, start the line.
	       */
	      mLine.start();
	    }
	    catch (LineUnavailableException e)
	    {
	      throw new RuntimeException("could not open audio line");
	    }   
	}

	
	/**
	 * Permanently disposes of all objects
	 */
	public void dispose() {
		closeJavaSound();
		if(inputStream != null) {
			try {
				inputStream.close();
			} catch(Exception e) {}
			inputStream = null;
		}

		if(texture != null) {
			texture.dispose();
			texture = null;
		}
		
		if (videoCoder != null) {
			videoCoder.close();
			videoCoder = null;
		}
		
		if (container != null) {
			container.close();
			container = null;
		}
	}
	
	private static void closeJavaSound()
	{
	    if (mLine != null)
	    {
	      /*
	       * Wait for the line to finish playing
	       */
	      mLine.drain();
	      /*
	       * Close the line.
	       */
	      mLine.close();
	      mLine=null;
	    }
	}
	
	/*
	 * A runnable that we use to write out the sound. By using these threads with SingleThreadExecutor
	 * we keep the audio always writing out without blocking the video decoding. This lets us
	 * play back the audio without skips or pops.*/
	private class WriteOutSoundBytes implements Runnable
	{
		private byte[] rawByte;
		
		public WriteOutSoundBytes(byte[] rawBytes, long timeStamp)
		{
			rawByte = rawBytes;

		}
		@Override
		public void run() 
		{
			
			mLine.write(rawByte, 0, rawByte.length);
			audioTimeStamp = mLine.getMicrosecondPosition();
		}
	}
	
	public int getCurretAudioFrameNumber()
	{
		int returnVal = 0;
		if(mLine != null && mLine.isActive())
		{
			returnVal = mLine.getFramePosition();
		}
		return returnVal; 
	
	}
	
	public int getCurrentVideoFrameNumber()
	{
		return this.videoFrame;
	}
	
	public long getAudioPlaytime()
	{
		long returnVal = 0;
		if(mLine != null && mLine.isActive())
		{
			returnVal = mLine.getMicrosecondPosition();
		}
		return returnVal; 	
	}
	
	public String audioPlayerActive()
	{
		if(mLine != null)
		{
			return Boolean.toString(mLine.isActive());
		}
		return "";
	}
	
	/** The packet handler is the heavy lifter of our video player. It decodes a packet
	 * and puts it in the appropriate queue (video or audio). If it gets too far ahead,
	 * the thread sleeps while the player outputs the video and catches up. */
	private class PacketHandler implements Runnable
	{
		private IPacket packet;
		private IContainer container;
		private IAudioSamples sample;
		private IVideoPicture picture;
		
		/*
		 * shortening the prebuffer effectively defers video decoding over a longer period
		 * we do this as an optimization strategy to avoid stutters in the video image
		 * since the video syncs off the audio (and not the opposite)
		 */
		private final long PREBUFFER = 10;
		
		Queue<VideoFrame> pictures;
		Queue<AudioFrame> samples;
		Lock readLock;

		private IStreamCoder aCoder;
		private IStreamCoder vCoder;
		private boolean decoderComplete;
		
		private IConverter converter;
		
		
		public PacketHandler(IContainer container)
		{
			
			this.packet = IPacket.make();
			this.container = container.copyReference();
			this.sample = IAudioSamples.make(1024, audioCoder.getChannels());
			
			
			this.picture = IVideoPicture.make(
					videoCoder.getPixelType(),
					videoCoder.getWidth(),
					videoCoder.getHeight()
				);
			this.aCoder = audioCoder.copyReference();
			this.vCoder = videoCoder.copyReference();
			this.readLock = new ReentrantLock();
			this.samples = new Queue<AudioFrame>();
			this.pictures = new Queue<VideoFrame>();
			
		}
		
		@Override
		public void run() 
		{
				//the sleep logic is contained here
				while(this.container.readNextPacket(packet) >= 0) {
				
					//need to limit the number of video packets we decode to preserve heap space	
					if(samples.getCount() >= 2)
					{
						while(pictures.getCount() >= 2 
										&& pictures.peekLast().timeStamp - pictures.peekNext().timeStamp > PREBUFFER * 1000)
						{
							try {
								Thread.sleep((int)(pictures.peekLast().timeStamp - pictures.peekNext().timeStamp)/2000 );
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
						
					}
					
					//if it's a video packet, decode it, and create our VideoFrame and put it in the queue
					if(packet.getStreamIndex() == videoStreamId) 
					{
						// Attempt to read the entire packet
						int offset = 0;
						while(offset < this.packet.getSize()) {
							// Decode the video, checking for any errors
							int bytesDecoded = vCoder.decodeVideo(this.picture, this.packet, offset);
							
							if (bytesDecoded < 0) {
								throw new RuntimeException("Got error decoding video");
							}
							offset += bytesDecoded;

							/* Some decoders will consume data in a packet, but will not
							 * be able to construct a full video picture yet. Therefore
							 * you should always check if you got a complete picture
							 * from the decoder
							 */
							if (this.picture.isComplete()) {
								IVideoPicture pic = IVideoPicture.make(this.picture);
								pic.setTimeStamp(this.picture.getTimeStamp());
								IVideoPicture newPic = picture.copyReference();
								

								//converts the image to the desired format, added some optimization here, since the
								//old code was using an old inefficient method for doing this
								if(this.converter == null)
									this.converter = ConverterFactory.createConverter(ConverterFactory.XUGGLER_BGR_24, newPic);
								BufferedImage javaImage = converter.toImage(newPic);
								readLock.lock();
								pictures.enqueue(new VideoFrame(javaImage, picture.getTimeStamp()));
								readLock.unlock();
							}
						}
					}
					//if it's an audio file, create an audioframe and put it in the queue
					else if(packet.getStreamIndex() == audioStreamId)
					{
						int offset = 0;
						while(offset < this.packet.getSize())
				        {
							 int bytesDecoded = aCoder.decodeAudio(this.sample, this.packet, offset);
							 if (bytesDecoded < 0)
								 throw new RuntimeException("got error decoding audio in: " + videoPath);
					          offset += bytesDecoded;
				        }
						if(sample.isComplete())
						{
							readLock.lock();
							samples.enqueue(new AudioFrame(this.sample.getData().getByteArray(0, sample.getSize()), sample.getTimeStamp()));
							readLock.unlock();
						}
						
					}
					
				}
				Gdx.app.log("Status","Video decoding complete");
				this.decoderComplete = true;
				//wait until notified that packets are done draining
				synchronized(this)
				{
					try {
						this.wait();
					} catch (InterruptedException e) {
						videoComplete = true;
						this.container.close();
						e.printStackTrace();
					}
				}
				Gdx.app.log("Status", videoPath + " completed playing successfully.");
				videoComplete = true;
				this.container.close();
		}
		
		
		public boolean isDecoderComplete() {
			return decoderComplete;
		}

		public VideoFrame getVideoFrame()
		{
			VideoFrame picture = null;
			
			if(pictures.getCount() > 0)
			{
				readLock.lock();
				VideoFrame testFrame = pictures.peekNext();
				//check if we're ready for the frame, if so, dequeue
				if(testFrame != null && testFrame.timeStamp <= audioTimeStamp + SYNC_TOLERANCE_MICROSECONDS)
				{
					picture = pictures.dequeue();
				}
				readLock.unlock();
			}
			return picture;
		}
		
		public AudioFrame getAudioFrame()
		{
			AudioFrame sample =null;
			if(samples.getCount() > 0)
			{
				readLock.lock();
				sample = samples.dequeue();
				readLock.unlock();
			}
			return sample;
		}

		public int getNumAudioPackets() {
			return samples.getCount();
		}

		public int getNumVideoPackets() {
			return pictures.getCount();
		}
	}
	
	private class VideoFrame
	{
		public BufferedImage image;
		public long timeStamp;
		public VideoFrame(BufferedImage image, long timeStamp)
		{
			this.image = image;
			this.timeStamp = timeStamp;
		}
	}
	
	private class AudioFrame
	{
		
		public byte[] byteArray;
		public long timeStamp;
		public AudioFrame(byte[] byteArray, long timeStamp)
		{
			this.byteArray = byteArray;
			this.timeStamp = timeStamp;
		}
	}

	public int getNumAudioPackets() {
		if(packetHandlerRunnable != null)
			return this.packetHandlerRunnable.getNumAudioPackets();
		else 
			return 0;
	}

	public int getNumVideoPackets() {
		if(packetHandlerRunnable != null)
			return this.packetHandlerRunnable.getNumVideoPackets();
		else
			return 0;
	}
		
}
	


