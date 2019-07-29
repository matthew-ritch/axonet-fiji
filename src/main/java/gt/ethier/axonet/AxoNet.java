package gt.ethier.axonet;


import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.RGBStackMerge;
import ij.WindowManager;
import ij.process.ImageProcessor;
import ij.process.FloatProcessor;

import java.util.List;

import net.imagej.DatasetService;
import ij.CompositeImage;
import ij.IJ;
import net.imagej.tensorflow.TensorFlowService;
//import net.imglib2.type.numeric.RealType;

import org.scijava.Priority;
import org.scijava.command.Command;
import org.scijava.io.http.HTTPLocation;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.log.LogLevel;

import org.tensorflow.SavedModelBundle;
import org.tensorflow.Tensor;
import org.tensorflow.framework.MetaGraphDef;
import org.tensorflow.framework.SignatureDef;
import org.tensorflow.framework.TensorInfo;

import com.google.protobuf.InvalidProtocolBufferException;

import java.util.Objects;


import org.la4j.Matrix;
import org.la4j.Vector;




//@Plugin(type = Command.class, menuPath = "Plugins>AxoNet")
@Plugin(type = Command.class, menuPath = "Analyze>AxoNet", priority = Priority.EXTREMELY_HIGH )
public class AxoNet implements Command {
		
		//define model identifiers
		//explained in https://www.tensorflow.org/api_docs/python/tf/saved_model/simple_save
		private static final String MODEL_URL = "https://github.com/matthew-ritch/AxoNet-fiji/raw/master/AxoNet-model.zip"; //downloads model from our github
		private static final String MODEL_NAME = "model_3"; 
		// Same as the tag used in export_saved_model in the Python code.
		private static final String MODEL_TAG = "serve";  //check when saving model
		private static final String DEFAULT_SERVING_SIGNATURE_DEF_KEY ="serving_default"; //leave unchanged
		private static final int TILE_SIZE =256; //mess with this to optimize performance. Should keep around 256, must be multiple of 32.
		//define services for our plugin
		@Parameter
		private static TensorFlowService tensorFlowService; //service for working with tensorflow    https://javadoc.scijava.org/ImageJ/net/imagej/tensorflow/TensorFlowService.html
		@Parameter
		private DatasetService datasetService; //service for working with datasets    https://javadoc.scijava.org/ImageJ/net/imagej/DatasetService.html	
		@Parameter
		private static LogService log; //sets up log service	
		//@Parameter(label = "Optic Nerve Cross Section")
		//private Img<T> originalImage;
		@Parameter(label = "<html>What scale is your image, in pixels per micron? " + "\nIf you don't know just hit OK.", persist = false,                          
				description = "<html>What scale is your image, in pixels per micron?", min = "10", max = "20")
			private double Scale = 15.7;
		//TODO add these
		/*
		@Parameter(label = "<html>Do you want a grid overlay on the final full count display? " + "This may help with viewing a large image's results.", persist = false,                          
				description = "<html>Do you want a grid overlay on the final full count display? " +
					"This may help with viewing a large image's results.")
			private boolean grid = true;
		@Parameter(label = "<html>Do you want a .csv output file of the pixelwise count densities used to calculate the full count?", persist = false,                          
				description = "<html> prints result count density to .csv in home folder")
			private boolean csvOut = true;
		*/
		
		@Override
		public void run() {
			//display error level to get the log to open
			log.error("Opening log window...\n");
			log.log(LogLevel.INFO, "AxoNet is running now!");
			//load image
			ImagePlus img = WindowManager.getCurrentImage(); 
			//warn if no image is loaded
			if (Objects.isNull(img)) {
				log.error("Load an image before running the plugin");
			}
			ImageProcessor imp = img.getProcessor(); 
			//open greyscale version, close original version
			ImagePlus progress = new ImagePlus("Nerve Image", imp);
			ImageProcessor grayscale = imp.convertToFloat();
			progress.setProcessor(grayscale);
			img.close();
			progress.show();
			
			
			
			//define full image sizing 
			int height = grayscale.getHeight();
			int width = grayscale.getWidth();
			System.out.println(height);
			System.out.println(width);
			
			//amout to scale by to return to 15.7 pixels/micron, as needed by the model
			double compensate = 15.7/Scale;
			System.out.println(compensate);
			System.out.println(compensate*height);
			System.out.println(compensate*width);
			//rescale, redefine total height and width, and set tile sizes
			try {
				grayscale=grayscale.resize((int) Math.round(compensate*width), (int) Math.round(compensate*height));
			}
			catch (Exception e) {
				String msg = ("Error- Image was not resized. Contining with original image.\n");
				log.log(LogLevel.INFO, msg);
			}
			
			//check scale from input
			IJ.run("Set Scale...", "distance=" + Double.toString(15.7) + " known=1 unit=micron");
			
			//redefine full iamge sizing after scaling
			height = grayscale.getHeight();
			width = grayscale.getWidth();
			System.out.println(height);
			System.out.println(width);
			progress.setProcessor(grayscale);
			//define tile sizes
			int tileCountRow = Math.round(height/TILE_SIZE);
			int tileCountCol = Math.round(width/TILE_SIZE);
			if (tileCountRow==0) { tileCountRow=1;}
			if (tileCountCol==0) { tileCountCol=1;}
			int tileWidth = (int) Math.floor(width/tileCountCol);
			int tileHeight = (int) Math.floor(height/tileCountRow);
			if (tileWidth%2!=0) { tileWidth=tileWidth-1;}
			if (tileHeight%2!=0) { tileHeight=tileHeight-1;}
			
			
			//define mirror size
			int mirrorMin = 16;
			int mirrorNheight = mirrorMin + (32-(tileHeight+2*mirrorMin)%32)/2; //mirrorMin minimum but adds to cover size issues on edges
			int mirrorNwidth = mirrorMin + (32-(tileWidth+2*mirrorMin)%32)/2; //mirrorMin minimum but adds to cover size issues on edges
			
			
			//load model from github download link
			log.log(LogLevel.INFO, "Getting the AxoNet model ready...");
			SavedModelBundle model = getModel();
						
			
			//make image matrix- casting an imagePlus to a float array. ImageJ indexes images as [x][y], so the output array is float[width][height]. We transpose this for [rows][cols] convention.
			double[][] imArray = toDoubleArray(grayscale.getFloatArray());
			Matrix imMat = Matrix.from2DArray(imArray);
			//clear imArray
			imArray=null;
			//transpose for [rows][cols] convention
			imMat=imMat.transpose();
			//initialize holders for indices
			int r[]=new int[2];
			int c[]=new int[2];
			
			
			/*
			 * Iterates over full image, splits into subregions, and normalizes along the standard scheme 
			 * 
			 */
			//TODO make patchwise
			String msg = ("Splitting image into subregions and processing...");
			log.log(LogLevel.INFO, msg);
			//log.info(msg);
			
			Matrix fullOutput= Matrix.zero(height, width);
			float[][] floatDensityOutput = new float[height][width];
			
			long start = System.nanoTime();
			for (int i=0; i< tileCountRow; i++) {
				for (int j=0; j< tileCountCol; j++) {
					
					r[0]=i*tileHeight;
					r[1]=(i+1)*tileHeight;
					c[0]=j*tileWidth;
					c[1]=(j+1)*tileWidth;
					
					//2D indexing, add to full array of regions
					//fromrow, fromcol, torow, tocol. goes up to torow/tocol but does not include them, so second two indices should each be one more than where is wanted
					Matrix thisIm = imMat.slice(r[0], c[0], r[1], c[1]);
					boolean skip=false;
					//normalize values by split intensities
					//If tile is fully black (zeros), treat it the same as an all white tile (ones)
					if (thisIm.max() != 0) {
						//normalize by max to get [0,1] scale
						thisIm=thisIm.divide(thisIm.max());
						double tot=thisIm.sum();
						//System.out.println(tot);
						if ((tot>.90*tileWidth*tileHeight) | (tot<.10*tileWidth*tileHeight))  {
							//leave this tile alone if mostly white or black, is likely background
						}
						else {
							//normalize image by subtracting mean pixel value and dividing by 2*SD of pixel value. This makes output about [-1,1]
							double[] SumStd = modSumStd(thisIm);
							if (SumStd[1]!=0) {
								thisIm=thisIm.subtract(SumStd[0]).divide(SumStd[1]*2);
							}
							//if stdev of image = 0, then something weird happened. use standard interior stdev as stand-in
							else {
								thisIm=thisIm.subtract(SumStd[0]).divide(.15);
							}
							
						}
					}
					else {
						//do not bother processing tile if background is all black
						skip=true;
					}
					//mirror edges
					thisIm=mirrorer(thisIm, mirrorNheight, mirrorNwidth);
					Matrix intermed = Matrix.zero(tileHeight, tileWidth);
					if (!skip) {
						//make input a tensor of undefined type and 4 dimensions
						//makes Matrix-> DenseMatrix-> 2D float array -> 4D float array -> 4D tensor in one line
						Tensor<?> input = Tensor.create(   addDims(toFloatArray(thisIm.toDenseMatrix().toArray()))    ); 
						//free regionArray[i][j] to memory
						
						//this section taken from microscopeImageFocusQualityClassifier
						SignatureDef sig = null;
						try {
							sig = MetaGraphDef.parseFrom(model.metaGraphDef()) //define signature
									.getSignatureDefOrThrow(DEFAULT_SERVING_SIGNATURE_DEF_KEY);
							
						} catch (InvalidProtocolBufferException e) {
							// Catch if model does not parse signature statement
							e.printStackTrace();
						}
						
						final List<Tensor<?>> fetches = model.session().runner() // run model with specified inputs, outputs, and operation. input/output names defined in serving code, not in this package
								.feed(opName(sig.getInputsOrThrow("input_image")), input) //
								.fetch(opName(sig.getOutputsOrThrow("output_map"))) //
								.run();
						
						//get the results back from tensor format
						float[][][][] dst = new float[1][tileHeight+2*mirrorNheight][tileWidth+2*mirrorNwidth][1]; // initialize intermediate variable
						fetches.get(0).copyTo(dst);  //fetch output tensor, dimensions=4, then copy from tensor to java float array
						//reverse mirroring. slice goes up to but does not include the last value, so param 3 and 4 should be one more than where we want to stop the index
						//also make matrix from double array and write it to our output array
						intermed = Matrix.from2DArray(toDoubleArray(removeDims(dst))).slice(mirrorNheight, mirrorNwidth, tileHeight+mirrorNheight, tileWidth+mirrorNwidth); 
						
					}
					else {
						//make zero matrix if is all black
						intermed = Matrix.zero(tileHeight, tileWidth);	
					}
					
					float[][] slice = toFloatArray(intermed.toDenseMatrix().toArray());
					
					//write this slice to appropriate place in float array output
					for (int i1=0; i1< tileHeight; i1++) {
						for (int j1=0; j1< tileWidth; j1++) {
							floatDensityOutput[i1+i*tileHeight][j1+j*tileWidth]=slice[i1][j1];
						}
					}
					
					
					
					
					
					if (j==0) {
						msg = (Double.toString(100*i/(tileCountRow)) + "% percent finished with applying to full image.");
						log.log(LogLevel.INFO, msg);
						msg = (Long.toString((System.nanoTime()-start)/1000000000) + " sec elapsed.");
						log.log(LogLevel.INFO, msg);
					}
					
				}			
				
			}
			//free full image for memory
			//imMat=null;
			long finish=System.nanoTime();
			msg = ("100% finished with applying model to the full image. Time elapsed = " + Long.toString(((finish-start)/1000000000)/60) + " min.");
			log.log(LogLevel.INFO, msg);
			
			//convert from float array to matrix
			fullOutput=Matrix.from2DArray(toDoubleArray(floatDensityOutput));
			//free memory
			floatDensityOutput = null;
			fullOutput=fullOutput.divide(1000); //divide by 1000 to undo output scaling used during model training
			//calculate full image axon count
			double sum = fullOutput.sum();
			//scale for eace of viewing and convert this full matrix back to double array
			fullOutput=fullOutput.divide(fullOutput.max()*1/4 );
			fullOutput.multiply(Math.pow(2, 32)); //normalize to 32bit for the FloatProcessor and display
			//Transpose back because ImagePlus uses [x][y] indexing and [cols][rows] scheme
			double[][] densityMap = fullOutput.transpose().toDenseMatrix().toArray(); //transpose back and make double array
			//free memory
			fullOutput=null;
			//display heat map
			FloatProcessor densityMapFlp = new FloatProcessor(toFloatArray(densityMap));
			ImagePlus display = new ImagePlus("Count Density Map", densityMapFlp);
			
			msg = ("Total count = " + Double.toString(sum));
			log.log(LogLevel.INFO, msg + "\n\n\n");
			display.show();
			
			//Make results window
			
			ResultsTable show = new ResultsTable();
			show.incrementCounter();
			show.addValue(0, sum);
			show.addLabel("Image Axon Count");
			//show.updateResults();
			show.show("Axon Count Results");
			
			//make overaly image output
			ImagePlus[] stack = new ImagePlus[7];
			stack[3]=progress;
			stack[5]=display;
			RGBStackMerge merger= new RGBStackMerge();
			ImagePlus merge = merger.mergeHyperstacks(stack, true);
			merge.show();
			
			
		}
			
		
		
		
		
	    /**
	     * Computes modified mean and standard deviation of image intensity 
	     *(does not consider brightest regions)
	     * 
	     *
	     * 
	     */
	    double[] modSumStd(Matrix in) {
	    	double[] values = new double[2];
	    	int n = 0;
	    	for (int i = 0; i < in.rows(); i++) {
				  for (int j = 0; j < in.columns(); j++) {
					  double val = in.get(i, j);
					  if (val<.90) {
						  values[0] = values[0] + val;
						  n=n+1;
					  }
				  }
			 }
	    	
	    	values[0]=values[0]/n;
	    	double diffs = 0;
	    	n=0;
	    	
	    	for (int i = 0; i < in.rows(); i++) {
				  for (int j = 0; j < in.columns(); j++) {
					  double val = in.get(i, j);
					  if (val<.90) {
						  diffs = diffs + Math.pow((val - values[0]),2);
						  n=n+1;
					  }
				  }
			 }
	    	values[1] = Math.sqrt(diffs/n);
	    	
	    	
			return values;
	    }
		
		double[][] toDoubleArray(float[][] arr) {
			  if (arr == null) return null;
			  int r = arr.length;
			  int c = arr[0].length;
			  double[][] ret = new double[r][c];
			  for (int i = 0; i < r; i++) {
				  for (int j = 0; j < c; j++) {
					  ret[i][j] = (double)arr[i][j];
				  }
			  }
			  return ret;
			}
		
		float[][] toFloatArray(double[][] arr) {
			  if (arr == null) return null;
			  int r = arr.length;
			  int c = arr[0].length;
			  float[][] ret = new float[r][c];
			  for (int i = 0; i < r; i++) {
				  for (int j = 0; j < c; j++) {
					  ret[i][j] = (float)arr[i][j];
				  }
			  }
			  return ret;
			}
		
		
		 private static SavedModelBundle getModel() {
				//load model
				HTTPLocation source = null;
				SavedModelBundle model = null;
				try {source = new HTTPLocation(MODEL_URL);} catch (final Exception e) {log.error(e);}
				try {model = tensorFlowService.loadModel(source, MODEL_NAME, MODEL_TAG);}
				catch (final Exception e) { log.error(e);} // Use the LogService to report the error
				return model;
			}
		
		
		// 
		//  
		 /**Returns a matrix with mirrored edges along the pattern used to train AxoNet.
			 * mirrors to the point of dimensions as multiples of 32
			 * 
			 *
			 * @param n number of pixel rows to expand in each direction
			 * @return matrix with mirrored edges.
			 */
		Matrix mirrorer(Matrix region, int mirrorNheight, int mirrorNwidth) {
			for (int i=0; i<mirrorNheight; i++) {
				//System.out.println(region.rows());
				region=region.insertRow(0, region.getRow(2*i));
				//System.out.println(region.rows());
				//region=region.insertRow(region.rows(), region.getRow((region.rows()-1)-2*i));
				region=addRow(region, region.rows(), region.getRow((region.rows()-1)-2*i));
			}
			for (int i=0; i<mirrorNwidth; i++) {
				region=region.insertColumn(0, region.getColumn(2*i));
				//region=region.insertColumn(region.columns(), region.getColumn((region.columns()-1)-2*i));
				region=addColumn(region, region.columns(), region.getColumn((region.columns()-1)-2*i));
			}
			return region;
		}
		
		
		/**
		 * The SignatureDef inputs and outputs contain names of the form
		 * {@code <operation_name>:<output_index>}, where for this model,
		 * {@code <output_index>} is always 0. This function trims the {@code :0}
		 * suffix to get the operation name.
		 */
		private static String opName(final TensorInfo t) {
			final String n = t.getName();
			if (n.endsWith(":0")) {
				return n.substring(0, n.lastIndexOf(":0"));
			}
			return n;
			
		}
		
		
		/**Modification from 'insertRow' to allow adding to end of row
		 * 
		 * Adds one row to matrix.
		 *
		 * @param i the row index
		 * @return matrix with row.
		 */
		public static Matrix addRow(Matrix to, int i, Vector row) {
		    if (i > to.rows() || i < 0) {
		        throw new IndexOutOfBoundsException("Illegal row number, must be 0.." + (to.rows() - 1));
		    }

		    Matrix result = to.blankOfShape(to.rows() + 1, to.columns());

		    for (int ii = 0; ii < i; ii++) {
		        result.setRow(ii, to.getRow(ii));
		    }

		    result.setRow(i, row);

		    for (int ii = i; ii < to.rows(); ii++) {
		        result.setRow(ii + 1, to.getRow(ii));
		    }

		    return result;
		}
		
		/**Modification from 'insertColumn' to allow adding to end of column
		 * 
		 * Adds one column to matrix.
		 *
		 * @param j the column index
		 * @return matrix with column.
		 */
		public static Matrix addColumn(Matrix to, int j, Vector column) {
		    if (j > to.columns() || j < 0) {
		        throw new IndexOutOfBoundsException("Illegal row number, must be 0.." + (to.columns() - 1));
		    }

		    Matrix result = to.blankOfShape(to.rows(), to.columns() + 1);

		    for (int jj = 0; jj < j; jj++) {
		        result.setColumn(jj, to.getColumn(jj));
		    }

		    result.setColumn(j, column);

		    for (int jj = j; jj < to.columns(); jj++) {
		        result.setColumn(jj + 1, to.getColumn(jj));
		    }

		    return result;
		}
		
		public static float[][][][] addDims(float [][] in) {
			float[][][][] out= new float[1][in.length][in[0].length][1];
			
			for (int i=0; i< in.length; i++) {
				for (int j=0; j< in[0].length; j++) {
					out[0][i][j][0]=in[i][j];
				}
			}
			
			return out;
		}
		
		public static float[][] removeDims(float [][][][] in) {
			float[][] out= new float[in[0].length][in[0][0].length];
			
			for (int i=0; i< in[0].length; i++) {
				for (int j=0; j< in[0][0].length; j++) {
					out[i][j] = in[0][i][j][0];
				}
			}
			
			return out;
		}
		
}






