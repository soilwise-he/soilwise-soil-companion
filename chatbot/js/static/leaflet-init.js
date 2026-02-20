/**
 * Leaflet Map Initialization
 *
 * This script detects map containers added to the DOM and initializes them
 * with Leaflet.js. It works with DOMPurify sanitization by using data attributes
 * instead of inline scripts.
 */

(function() {
  'use strict';

  let leafletLoaded = false;
  let leafletLoading = false;
  const pendingMaps = [];

  /**
   * Load Leaflet CSS
   */
  function loadLeafletCSS(url) {
    if (document.querySelector(`link[href="${url}"]`)) {
      return; // Already loaded
    }
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = url;
    document.head.appendChild(link);
  }

  /**
   * Load Leaflet JS
   */
  function loadLeafletJS(url) {
    return new Promise((resolve, reject) => {
      if (typeof L !== 'undefined') {
        resolve();
        return;
      }

      if (document.querySelector(`script[src="${url}"]`)) {
        // Already loading, wait for it
        const checkInterval = setInterval(() => {
          if (typeof L !== 'undefined') {
            clearInterval(checkInterval);
            resolve();
          }
        }, 100);
        return;
      }

      const script = document.createElement('script');
      script.src = url;
      script.onload = resolve;
      script.onerror = reject;
      document.head.appendChild(script);
    });
  }

  /**
   * Initialize a single map container
   */
  function initializeMap(container) {
    if (container.dataset.mapInitialized === 'true') {
      return; // Already initialized
    }

    try {
      const config = JSON.parse(container.dataset.mapConfig);
      const cssUrl = container.dataset.leafletCss;
      const jsUrl = container.dataset.leafletJs;

      // Check if Leaflet is already available (loaded in HTML)
      if (typeof L !== 'undefined') {
        leafletLoaded = true;
      }

      // Load Leaflet if not already loaded
      if (!leafletLoaded && !leafletLoading) {
        leafletLoading = true;
        loadLeafletCSS(cssUrl);
        loadLeafletJS(jsUrl).then(() => {
          leafletLoaded = true;
          leafletLoading = false;
          // Initialize all pending maps
          pendingMaps.forEach(c => initializeMap(c));
          pendingMaps.length = 0;
        }).catch(err => {
          console.error('Failed to load Leaflet:', err);
          leafletLoading = false;
          container.innerHTML = '<div style="color: #d32f2f; padding: 20px; text-align: center;">Failed to load map library</div>';
        });
        pendingMaps.push(container);
        return;
      }

      if (leafletLoading) {
        pendingMaps.push(container);
        return;
      }

      // Clear loading message
      container.innerHTML = '';
      container.style.display = 'block';

      // Create map
      const map = L.map(container, {
        center: config.center,
        zoom: config.zoom
      });

      // Add tile layer based on basemap type
      let tileLayer;
      switch (config.basemap) {
        case 'satellite':
          tileLayer = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
            attribution: 'Tiles &copy; Esri',
            maxZoom: 18
          });
          break;
        case 'terrain':
          tileLayer = L.tileLayer('https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png', {
            attribution: 'Map data: &copy; OpenStreetMap contributors, SRTM | Map style: &copy; OpenTopoMap',
            maxZoom: 17
          });
          break;
        default: // 'osm'
          tileLayer = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            attribution: '&copy; OpenStreetMap contributors',
            maxZoom: 19
          });
      }
      tileLayer.addTo(map);

      // Add WMS overlay layer if configured
      if (config.wmsUrl && config.wmsLayer) {
        const wmsLayer = L.tileLayer.wms(config.wmsUrl, {
          layers: config.wmsLayer,
          format: 'image/png',
          transparent: true,
          opacity: 0.7,
          attribution: '&copy; ISRIC SoilGrids'
        });
        wmsLayer.addTo(map);
      }

      // Track features to calculate bounds
      const features = L.featureGroup();

      // Add markers
      if (config.markers && config.markers.length > 0) {
        config.markers.forEach(marker => {
          const m = L.marker([marker.lat, marker.lon], {
            icon: L.divIcon({
              className: 'custom-marker',
              html: `<div style="background-color: ${marker.color}; width: 12px; height: 12px; border-radius: 50%; border: 2px solid white; box-shadow: 0 0 4px rgba(0,0,0,0.4);"></div>`,
              iconSize: [16, 16],
              iconAnchor: [8, 8]
            })
          });

          if (marker.popup) {
            m.bindPopup(marker.popup);
          }

          m.addTo(map);
          features.addLayer(m);
        });
      }

      // Add polygons
      if (config.polygons && config.polygons.length > 0) {
        config.polygons.forEach(polygon => {
          const coords = polygon.coordinates.map(c => [c[0], c[1]]);
          const p = L.polygon([coords], {
            color: polygon.strokeColor,
            fillColor: polygon.fillColor,
            fillOpacity: polygon.fillOpacity,
            weight: 2
          });

          if (polygon.label) {
            p.bindPopup(polygon.label);
          }

          p.addTo(map);
          features.addLayer(p);
        });
      }

      // Add scale control
      L.control.scale({ imperial: false, metric: true }).addTo(map);

      // Adjust view to fit features if any, otherwise use default center/zoom
      if (features.getLayers().length > 0) {
        map.fitBounds(features.getBounds(), { padding: [30, 30] });
      }

      // Fix rendering
      setTimeout(() => {
        map.invalidateSize();
      }, 100);

      container.dataset.mapInitialized = 'true';

    } catch (err) {
      console.error('Failed to initialize map:', err);
      container.innerHTML = '<div style="color: #d32f2f; padding: 20px; text-align: center;">Failed to initialize map: ' + err.message + '</div>';
    }
  }

  /**
   * Scan for map containers and initialize them
   */
  function scanForMaps() {
    const containers = document.querySelectorAll('.leaflet-map-container');
    containers.forEach(container => {
      if (container.dataset.mapInitialized !== 'true') {
        initializeMap(container);
      }
    });
  }

  /**
   * Set up a MutationObserver to detect new map containers
   */
  function setupObserver() {
    const observer = new MutationObserver((mutations) => {
      let shouldScan = false;
      mutations.forEach(mutation => {
        mutation.addedNodes.forEach(node => {
          if (node.nodeType === 1) { // Element node
            if (node.classList && node.classList.contains('leaflet-map-container')) {
              shouldScan = true;
            } else if (node.querySelector && node.querySelector('.leaflet-map-container')) {
              shouldScan = true;
            }
          }
        });
      });

      if (shouldScan) {
        // Delay slightly to allow DOM to settle
        setTimeout(scanForMaps, 50);
      }
    });

    observer.observe(document.body, {
      childList: true,
      subtree: true
    });
  }

  // Initialize on DOM ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
      scanForMaps();
      setupObserver();
    });
  } else {
    scanForMaps();
    setupObserver();
  }

  // Also scan periodically as a fallback
  setInterval(scanForMaps, 2000);

  // Expose global function for manual triggering from Scala.js
  window.initLeafletMaps = scanForMaps;

})();
