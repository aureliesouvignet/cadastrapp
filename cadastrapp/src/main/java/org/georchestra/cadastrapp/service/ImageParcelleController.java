package org.georchestra.cadastrapp.service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;

import org.geotools.data.ows.Layer;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.data.wfs.WFSDataStore;
import org.geotools.data.wfs.WFSDataStoreFactory;
import org.geotools.data.wms.WebMapServer;
import org.geotools.data.wms.request.GetMapRequest;
import org.geotools.data.wms.response.GetMapResponse;
import org.geotools.filter.text.cql2.CQL;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.geometry.jts.JTS;
import org.geotools.ows.ServiceException;
import org.geotools.referencing.CRS;
import org.opengis.filter.Filter;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.geometry.BoundingBox;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.NoSuchAuthorityCodeException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.TransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.awt.ShapeWriter;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;

/**
 * Image Parcelle Controller
 * 
 * @author gfi
 *
 */
public class ImageParcelleController extends CadController {

	final static Logger logger = LoggerFactory.getLogger(ImageParcelleController.class);

	final private String URL_GET_CAPABILITIES = "?REQUEST=GetCapabilities&version=1.0.0";

	final private String URL_GET_CAPABILITIES_WMS = "?VERSION=1.1.1&Request=GetCapabilities&Service=WMS";

	final private String CONNECTION_PARAM = "WFSDataStoreFactory:GET_CAPABILITIES_URL";

	// buffer distance in CRS unit
	final double bufferDistance = 10.0;

	final private String ESPG3857 = "EPSG:3857";

	final private String ESPG900913 = "EPSG:900913";

	/**
	 * Using a given parcelle id, this service will get feature from WFS
	 * service, get Basemap image using Boundingbox of the feature and add
	 * compass and scale on it
	 * 
	 * @param parcelle
	 *            parcelle ID
	 * 
	 * @return Response with noContent in case of error, png otherwise
	 * 
	 */
	@GET
	@Path("/getImageBordereau")
	@Produces("image/png")
	public Response createImageBordereauParcellaire(@QueryParam("parcelle") String parcelle) {

		// Create empty reponse for default value
		ResponseBuilder response = Response.noContent();

		// Check parcelle value, at least
		if (parcelle != null && parcelle.length() > parcelleIdLength) {

			int distanceVisible = 0;
			BufferedImage baseMapImage;
			BufferedImage parcelleImage;
			BoundingBox bounds;

			// Get parcelle geo information
			// get featureById
			String getCapabilities = wfsUrl + URL_GET_CAPABILITIES;

			logger.debug("Call WFS with plot Id " + parcelle + " and WFS URL : " + getCapabilities);

			Map<String, String> connectionParameters = new HashMap<String, String>();
			connectionParameters.put(CONNECTION_PARAM, getCapabilities);
			WFSDataStoreFactory dsf = new WFSDataStoreFactory();

			WFSDataStore dataStore;

			try {
				dataStore = dsf.createDataStore(connectionParameters);

				SimpleFeatureSource source;

				source = dataStore.getFeatureSource(cadastreWFSLayerName);

				// Make sure source have been found before making request filter
				if (source != null) {
					Filter filter = CQL.toFilter(cadastreLayerIdParcelle + " = '" + parcelle + "'");
					SimpleFeatureCollection collection = source.getFeatures(filter);

					SimpleFeatureIterator it = collection.features();

					// Check if there is a leat one feature
					if (it.hasNext()) {

						logger.debug("Get feature");
						// Get only the first plot
						SimpleFeature parcelleFeature = it.next();

						CoordinateReferenceSystem crs = parcelleFeature.getBounds().getCoordinateReferenceSystem();

						// ESPG3857 is not known by geotools 10.8 so changeit by
						// ESPG900913
						if (cadastreSRS.equals(ESPG3857)) {
							crs = CRS.decode(ESPG900913);
						}

						bounds = parcelleFeature.getBounds();
						Geometry targetGeometry = null;

						// If CRS null
						if (crs == null) {
							logger.error("CRS not known by geotools, no buffering can be made, scale won't be seeing on image");

						} else {
							logger.debug("CRS : " + crs);

							logger.debug("Create buffer");
							targetGeometry = (Geometry) parcelleFeature.getDefaultGeometry();
							targetGeometry = targetGeometry.buffer(bufferDistance);

							// transform JTS enveloppe to geotools enveloppe
							Envelope envelope = targetGeometry.getEnvelopeInternal();

							bounds = JTS.getEnvelope2D(envelope, crs);

							// Get distance beetween two point here bounds is
							// used

							Coordinate start = new Coordinate(bounds.getMinX(), bounds.getMinY());
							Coordinate end = new Coordinate(bounds.getMaxX(), bounds.getMinY());

							try {
								double distance = JTS.orthodromicDistance(start, end, crs);
								distanceVisible = (int) distance;
								logger.debug("Bounding box length : " + distanceVisible + " meters");

							} catch (TransformException e) {
								logger.error("Could not calculate distance, no scale bar will be displayed on image", e);
							}

						}

						logger.debug("Call WMS for plot");
						// Get parcelle image with good BBOX
						URL parcelleWMSUrl = new URL(wmsUrl + URL_GET_CAPABILITIES_WMS);

						logger.debug("WMS URL : " + parcelleWMSUrl);
						WebMapServer wmsParcelle = new WebMapServer(parcelleWMSUrl);

						GetMapRequest requestParcelle = wmsParcelle.createGetMapRequest();
						requestParcelle.setFormat(cadastreFormat);

						// Add layer see to set this in configuration parameters
						// Or use getCapatibilities
						Layer layerParcelle = new Layer("Parcelle cadastrapp");
						layerParcelle.setName(cadastreWMSLayerName);
						requestParcelle.addLayer(layerParcelle);

						// sets the dimensions check with PDF size available
						requestParcelle.setDimensions(pdfImageWidth, pdfImageHeight);
						requestParcelle.setSRS(cadastreSRS);
						requestParcelle.setTransparent(true);

						// setBBox from Feature information
						requestParcelle.setBBox(bounds);

						logger.debug("Create feature picture");
						GetMapResponse parcelleResponse = (GetMapResponse) wmsParcelle.issueRequest(requestParcelle);
						parcelleImage = ImageIO.read(parcelleResponse.getInputStream());

						logger.debug("Create final picture");
						BufferedImage finalImage = new BufferedImage(parcelleImage.getWidth(), parcelleImage.getHeight(), BufferedImage.TYPE_INT_ARGB);

						Graphics2D g2 = finalImage.createGraphics();

						logger.debug("WMS call for basemap with URL : " + baseMapWMSUrl);

						// Get basemap image with good BBOX
						try {
							URL baseMapUrl = new URL(baseMapWMSUrl);
							WebMapServer wms = new WebMapServer(baseMapUrl);

							GetMapRequest request = wms.createGetMapRequest();
							request.setFormat(baseMapFormat);

							// Add layer see to set this in configuration
							// parameters
							// Or use getCapatibilities
							Layer layer = new Layer("OpenStreetMap : carte style 'google'");
							layer.setName(baseMapLayerName);
							request.addLayer(layer);

							// sets the dimensions check with PDF size available
							request.setDimensions(pdfImageWidth, pdfImageHeight);
							request.setSRS(baseMapSRS);

							// setBBox from Feature information
							request.setBBox(bounds);

							GetMapResponse baseMapResponse;

							baseMapResponse = (GetMapResponse) wms.issueRequest(request);
							logger.debug("Create basemap picture");
							baseMapImage = ImageIO.read(baseMapResponse.getInputStream());
							g2.drawImage(baseMapImage, 0, 0, null);
						} catch (ServiceException e) {
							logger.error("Error while getting basemap image, no basemap will be displayed on image", e);
						} catch (IOException e) {
							logger.error("Error while getting basemap image, no basemap will be displayed on image", e);
						}

						logger.debug("Add feature to final picture");
						g2.drawImage(parcelleImage, 0, 0, null);

						drawPlot(g2, targetGeometry);
						drawCompass(g2, pdfImageHeight, pdfImageWidth);

						try {
							drawScale(g2, pdfImageHeight, pdfImageWidth, distanceVisible);
						} catch (TransformException e) {
							logger.warn("Error while creating scale bar, no scale bar will be displayed on image", e);
						}

						g2.dispose();

						File file = new File(tempFolder + File.separator + "BP-" + parcelle + ".png");
						file.deleteOnExit();
						ImageIO.write(finalImage, "png", file);

						response = Response.ok((Object) file);
					} else {
						logger.info("No plots corresponding on WFS server");
					}
				} else {
					logger.error("Error when getting WFS feature source, please check configuration");
				}
			} catch (IOException e) {
				logger.error("Error while trying to init connection, please check configuration", e);
			} catch (NoSuchAuthorityCodeException e) {
				logger.error("Error while trying to decode CRS", e);
			} catch (FactoryException e) {
				logger.error("Error while trying to decode CRS", e);
			} catch (ServiceException e) {
				logger.error("Error while trying to connect to WMS server", e);
			} catch (CQLException e) {
				logger.error("Error while trying to create CQL filter", e);
			}
		} else {
			logger.info("No image can be generated with given input parameters");
		}

		return response.build();
	}

	/**
	 * Add North panel in the Upper Righ
	 * 
	 * @param g2
	 * @param imageHeight
	 * @param imageWidth
	 */
	private void drawCompass(Graphics2D g2, int imageHeight, int imageWidth) {

		logger.debug("Add compass ");

		// Draw N in the Upper Right
		g2.setColor(Color.white);
		g2.setFont(new Font("Times New Roman", Font.BOLD, 14));
		g2.drawString("N", imageWidth - 32, 22);

		// Draw an arrow in the Upper Right
		int xtr_left[] = { imageWidth - 32, imageWidth - 25, imageWidth - 25 };
		int ytr[] = { 44, 42, 27 };
		int xtr_right[] = { imageWidth - 19, imageWidth - 25, imageWidth - 25 };
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.fillPolygon(xtr_right, ytr, 3);
		g2.setStroke(new BasicStroke(1.0f));
		g2.drawPolygon(xtr_left, ytr, 3);
		g2.drawPolygon(xtr_right, ytr, 3);
	}

	/**
	 * Add a scale in bottom left of images
	 * 
	 * @param g2
	 * @param imageHeight
	 * @param imageWidth
	 * @param distanceVisible
	 * 
	 * @throws TransformException
	 */
	private void drawScale(Graphics2D g2, int imageHeight, int imageWidth, int distanceVisible) throws TransformException {

		logger.debug("Add scale ");

		if (distanceVisible > 0) {
			// define 1 pt size in meters
			final int pixelSize = imageWidth / distanceVisible;

			// Start x and y for scale bar
			final int scaleX = 50;
			final int scaleY = imageHeight - 10;

			// Width of the scalebar
			final int width = 100;
			final int divisionCount = 2;

			final int distance = width * pixelSize / divisionCount;
			final String unit = "mètres";
			final String Zmin = "0";
			final String Zmax = distance + " " + unit;

			// Create grey globla rectangle with label and scale bar in it
			g2.setColor(new Color(255, 255, 255, 127));
			g2.fill(new Rectangle2D.Double(scaleX, scaleY - 23, width + 10, 23));

			final int scalebare = (int) Math.round(width / divisionCount);
			for (int i = 0; i < divisionCount; i++) {
				if (i % 2 == 0) {
					g2.setColor(new Color(83, 83, 83, 115));
				} else {
					g2.setColor(new Color(25, 25, 25, 175));
				}
				g2.setColor(new Color(83, 83, 83, 115));
				g2.fill(new Rectangle2D.Double(scaleX - 5, scaleY - 10, scalebare, 5));
			}

			Font fnt = new Font("Verdana", Font.PLAIN, 11);
			FontMetrics fm = g2.getFontMetrics(fnt);
			final int fm_width = fm.stringWidth(Zmax);

			g2.setColor(Color.black);
			g2.setFont(fnt);

			g2.drawString(Zmin, scaleX, scaleY - 12);
			g2.drawString(Zmax, ((scaleX + width) - fm_width), scaleY - 12);
		} else {
			logger.warn("No scale bar can be create, wrong distance value given");
		}
	}

	/**
	 * Draw selected plot in blue
	 * @param g2
	 * @param geometry
	 */
	private void drawPlot(Graphics2D g2, Geometry geometry) {

		logger.debug("Add selected feature ");
		if (geometry != null) {
			
			// Transform JTS in awt
			ShapeWriter sw = new ShapeWriter();
			Shape plot = sw.toShape(geometry);

			// draw in blue with transparence
			g2.setColor(new Color(20, 255, 255, 128));
			g2.draw(plot);
			g2.setColor(new Color(20, 20, 255, 128));
			g2.fill(plot);
		} else {
			logger.error("No plot were given, cannot draw it on image");
		}

	}
}
