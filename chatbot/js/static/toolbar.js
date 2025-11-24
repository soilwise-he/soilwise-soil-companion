// Lightweight handlers for the chat toolbar
(function(){
  function byId(id){ return document.getElementById(id); }

  function showToast(msg){
    // Update the subtle footer status instead of showing intrusive alerts
    console.log('[toolbar]', msg);
    try {
      var status = document.getElementById('status-text');
      if (status) {
        status.textContent = msg;
        // Briefly highlight status (CSS optional). Remove class after timeout if present.
        status.classList && status.classList.add('status-pulse');
        setTimeout(function(){
          // Only reset to Ready if message hasn't changed since
          if (status.textContent === msg) {
            status.textContent = 'Ready.';
          }
          status.classList && status.classList.remove('status-pulse');
        }, 3000);
        return;
      }
    } catch(e) { /* ignore */ }
    // Fallback to console when footer is not available
    console.log('[toolbar][fallback]', msg);
  }

  function getChatText(){
    var container = document.getElementById('messages');
    if (!container) return '';
    var items = container.querySelectorAll('.message');
    if (!items || items.length === 0) return '';
    var lines = [];
    items.forEach(function(item){
      var isUser = item.classList.contains('user-message');
      var role = isUser ? 'Human' : 'AI';
      var timeEl = item.querySelector('.message-time');
      var time = timeEl ? (timeEl.textContent || '').trim() : '';
      var contentEl = item.querySelector('.message-content');
      var content = contentEl ? (contentEl.textContent || '').trim() : '';
      // Normalize multiple blank lines
      content = content.replace(/\n{3,}/g, '\n\n');
      // Build block: "[HH:MM] Human: text"
      var header = time ? ('[' + time + '] ' + role + ':') : (role + ':');
      if (content) {
        lines.push(header + ' ' + content);
      } else {
        lines.push(header);
      }
    });
    return lines.join('\n\n');
  }

  function copyToClipboard(text){
    if (!text) { showToast('Nothing to copy'); return; }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function(){
        showToast('Chat copied to clipboard');
      }).catch(function(){
        fallbackCopy(text);
      });
    } else {
      fallbackCopy(text);
    }
  }

  function fallbackCopy(text){
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.left = '-10000px';
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    try {
      document.execCommand('copy');
      showToast('Chat copied to clipboard');
    } catch (e) {
      showToast('Copy failed');
    } finally {
      document.body.removeChild(ta);
    }
  }

  function nowTimeString(){
    var d = new Date();
    var hh = String(d.getHours()).padStart(2, '0');
    var mm = String(d.getMinutes()).padStart(2, '0');
    return hh + ':' + mm;
  }

  function addWelcomeMessage(){
    var container = document.getElementById('messages');
    if (!container) return;
    var div = document.createElement('div');
    div.classList.add('message', 'bot-message');
    var time = nowTimeString();
    var content = 'Welcome back! I\'m ready to help with soil questions. Ask me anything to get started.';
    div.innerHTML = '' +
      '<div class="message-header">' +
      '  <i class="fa-solid fa-robot" aria-hidden="true"></i>' +
      '</div>' +
      '<div class="message-time">' + time + '</div>' +
      '<div class="message-content"></div>';
    var contentEl = div.querySelector('.message-content');
    if (contentEl) {
      // Sanitize just in case (same library used elsewhere)
      try {
        contentEl.innerHTML = DOMPurify.sanitize(content);
      } catch(e) {
        contentEl.textContent = content;
      }
    }
    container.appendChild(div);
    // auto-scroll to bottom
    try { container.scrollTop = container.scrollHeight; } catch(e) {}
  }

  function clearChat(){
    // Clear any uploaded file state immediately (UI + persistence) to avoid the chip reappearing
    try {
      var ind0 = document.getElementById('upload-indicator');
      if (ind0) {
        var t0 = ind0.querySelector('.upload-indicator-text');
        if (t0) t0.textContent = '';
        ind0.title = 'No file uploaded';
        ind0.setAttribute('hidden','');
      }
      try { localStorage.removeItem('soilcompanion.upload'); } catch(_) {}
    } catch(e) { /* ignore */ }

    var container = document.getElementById('messages');
    if (container) {
      container.innerHTML = '';
      // Immediately show a fresh welcome message in the new chat
      addWelcomeMessage();
    }
    // also clear backend chat memory for current session
    try {
      var sessionId = localStorage.getItem('soilcompanion.sessionId') || '';
      if (!sessionId) { showToast('No active session to clear'); return; }
      fetch('http://localhost:8080/clear', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: sessionId })
      }).then(function(resp){
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        return resp.text();
      }).then(function(){
        showToast('Chat cleared');
        var status = document.getElementById('status-text');
        if (status) status.textContent = 'Chat cleared.';
        // Ensure uploaded file indicator remains cleared and storage wiped
        try {
          var ind = document.getElementById('upload-indicator');
          if (ind) {
            var t = ind.querySelector('.upload-indicator-text');
            if (t) t.textContent = '';
            ind.title = 'No file uploaded';
            ind.setAttribute('hidden','');
          }
          try { localStorage.removeItem('soilcompanion.upload'); } catch(_) {}
        } catch(e) { /* ignore */ }
      }).catch(function(err){
        console.error('[toolbar] clear failed', err);
        showToast('Failed to clear chat: ' + err);
      });
    } catch (e) {
      console.error('[toolbar] clear exception', e);
    }
  }

  function onReady(){
    var saveBtn = byId('btn-save');
    var copyBtn = byId('btn-copy');
    var clearBtn = byId('btn-clear');
    var uploadBtn = byId('upload-button');
    var sendBtn = byId('send-button');
    var fileInput = byId('file-upload');
    var loginBtn = byId('login-button');
    var logoutBtn = byId('logout-button');
    var loginInfo = byId('login-info');

    function updateUploadIndicator(name, chars){
      try {
        var ind = document.getElementById('upload-indicator');
        if (!ind) return;
        var t = ind.querySelector('.upload-indicator-text');
        var pretty = String(name || '').trim();
        if (!pretty) return;
        var suffix = (typeof chars === 'number' && chars > 0) ? (' • ' + chars + ' chars') : '';
        if (t) t.textContent = pretty + suffix;
        ind.title = 'Uploaded: ' + pretty + suffix;
        ind.removeAttribute('hidden');
        // persist for restoration on UI toggles
        try { localStorage.setItem('soilcompanion.upload', JSON.stringify({ name: pretty, chars: (typeof chars==='number'?chars:0) })); } catch(_) {}
      } catch(e) { /* ignore */ }
    }

    function clearUploadIndicator(){
      try {
        var ind = document.getElementById('upload-indicator');
        if (!ind) return;
        var t = ind.querySelector('.upload-indicator-text');
        if (t) t.textContent = '';
        ind.title = 'No file uploaded';
        ind.setAttribute('hidden','');
        try { localStorage.removeItem('soilcompanion.upload'); } catch(_) {}
      } catch(e) { /* ignore */ }
    }

    function restoreUploadIndicator(){
      try {
        var raw = localStorage.getItem('soilcompanion.upload');
        if (!raw) return;
        var data = {};
        try { data = JSON.parse(raw); } catch(_) { data = {}; }
        if (data && data.name) {
          updateUploadIndicator(data.name, data.chars);
        }
      } catch(e) { /* ignore */ }
    }

    function setButtonsEnabled(enabled){
      [sendBtn, uploadBtn].forEach(function(btn){
        if (!btn) return;
        try {
          btn.disabled = !enabled;
          btn.setAttribute('aria-disabled', (!enabled).toString());
        } catch(e) {}
      });
      if (fileInput) {
        // no disabled attr for input[type=file] needed because we gate via uploadBtn
        if (!enabled) fileInput.value = '';
      }
      console.debug('[auth] action buttons', enabled ? 'enabled' : 'disabled');
    }

    function isAuthenticated(){
      try {
        // Only trust explicit auth signals set by the app logic.
        // 1) Primary: localStorage flag set on successful login
        var flag = localStorage.getItem('soilcompanion.auth');
        if (flag === '1') return true;
        // 2) Secondary: data attribute on the root (in case storage is unavailable)
        var root = document.querySelector('.app.layout');
        if (root && root.getAttribute('data-auth') === '1') return true;
        // Do not infer auth from UI visibility heuristics (can be stale/incorrect)
        return false;
      } catch(e) { return false; }
    }

    function applyAuthState(){
      var authed = isAuthenticated();
      setButtonsEnabled(authed);
      if (authed) {
        restoreUploadIndicator();
      } else {
        clearUploadIndicator();
      }
      var status = document.getElementById('status-text');
      if (status) status.textContent = authed ? 'Logged in — chat enabled.' : 'Please login to enable chat.';
    }

    if (saveBtn) saveBtn.addEventListener('click', function(){
      var name = (byId('chat-name') || {}).value || 'Untitled chat';
      showToast('Save not implemented yet ("' + name + '")');
    });

    if (copyBtn) copyBtn.addEventListener('click', function(){
      copyToClipboard(getChatText());
    });

    if (clearBtn) clearBtn.addEventListener('click', function(){
      clearChat();
    });

    function doUpload(file){
      if (!file) return;
      var name = (file.name || '').trim();
      var lower = name.toLowerCase();
      if (!lower.endsWith('.txt') && !lower.endsWith('.md')){
        showToast('Only .txt or .md files are allowed');
        return;
      }
      var maxBytes = 300000; // ~300KB hard cap client-side
      if (file.size > maxBytes){
        showToast('File too large (limit ~300 KB)');
        return;
      }
      var reader = new FileReader();
      reader.onload = function(){
        try {
          var text = String(reader.result || '');
          if (!text.trim()) { showToast('The file is empty'); return; }
          var sessionId = localStorage.getItem('soilcompanion.sessionId') || '';
          if (!sessionId) { showToast('No active session'); return; }
          console.debug('[upload] sending', { name: name, size: file.size });
          var status = document.getElementById('status-text');
          if (status) status.textContent = 'Uploading ' + name + ' ...';
          // Immediately show a provisional indicator so users see progress
          try {
            var ind0 = document.getElementById('upload-indicator');
            if (ind0) {
              var t0 = ind0.querySelector('.upload-indicator-text');
              if (t0) t0.textContent = name + ' • uploading…';
              ind0.title = 'Uploading ' + name + ' ...';
              ind0.removeAttribute('hidden');
              ind0.style.removeProperty && ind0.style.removeProperty('display');
            }
          } catch(e) { /* ignore */ }
          fetch('http://localhost:8080/upload', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId: sessionId, filename: name, content: text })
          }).then(function(resp){
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            // Read as text first to be resilient to non-JSON content-type
            return resp.text().then(function(body){ return { body: body }; });
          }).then(function(pkg){
            var res = {};
            try { res = JSON.parse(pkg.body); } catch(e) { res = {}; }
            var success = true; // default to success on HTTP 200
            if (typeof res.ok !== 'undefined') success = !!res.ok;
            if (success){
              var fname = (res.filename || name);
              var chars = (typeof res.chars === 'number') ? res.chars : text.length;
              showToast('Uploaded ' + fname + ' (' + chars + ' chars)');
              // Update indicator chip in the UI
              try { updateUploadIndicator(fname, chars); } catch(e) { /* ignore */ }
            } else {
              var err = (res && res.error) ? res.error : 'upload_failed';
              showToast('Upload failed: ' + err);
              // Revert the provisional indicator on failure
              try {
                var ind1 = document.getElementById('upload-indicator');
                if (ind1) { ind1.setAttribute('hidden',''); ind1.title = 'No file uploaded'; }
              } catch(e) { /* ignore */ }
            }
          }).catch(function(err){
            console.error('[upload] failed', err);
            showToast('Upload failed: ' + err);
            try {
              var ind2 = document.getElementById('upload-indicator');
              if (ind2) { ind2.setAttribute('hidden',''); ind2.title = 'No file uploaded'; }
            } catch(e) { /* ignore */ }
          });
        } catch(e){
          console.error('[upload] exception', e);
          showToast('Upload error');
          try {
            var ind3 = document.getElementById('upload-indicator');
            if (ind3) { ind3.setAttribute('hidden',''); ind3.title = 'No file uploaded'; }
          } catch(_) { /* ignore */ }
        }
      };
      reader.onerror = function(){
        console.error('[upload] read error', reader.error);
        showToast('Failed to read file');
      };
      reader.readAsText(file);
    }

    if (uploadBtn && fileInput){
      uploadBtn.addEventListener('click', function(){
        if (uploadBtn.disabled) { showToast('Please login to upload files.'); return; }
        fileInput.value = '';
        fileInput.click();
      });
      fileInput.addEventListener('change', function(ev){
        var f = (ev.target && ev.target.files && ev.target.files[0]) || null;
        doUpload(f);
      });
      // Support dropping a file onto the upload button for convenience
      uploadBtn.addEventListener('dragover', function(e){ if (uploadBtn.disabled) return; e.preventDefault(); });
      uploadBtn.addEventListener('drop', function(e){
        if (uploadBtn.disabled) return;
        e.preventDefault();
        var f = (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0]) || null;
        doUpload(f);
      });
    }

    // Apply initial auth state
    applyAuthState();

    // Observe login/logout UI changes
    try {
      var loginActions = (loginBtn && loginBtn.parentElement) || document.querySelector('.login-actions');
      if (logoutBtn) {
        var mo = new MutationObserver(function(){ applyAuthState(); });
        mo.observe(logoutBtn, { attributes: true, attributeFilter: ['style','class','hidden','aria-hidden'] });
      }
      if (loginActions) {
        var mo2 = new MutationObserver(function(){ applyAuthState(); });
        mo2.observe(loginActions, { childList: true, subtree: true });
      }
    } catch(e) { /* ignore */ }

    // Fallback: poll auth state occasionally (lightweight)
    setInterval(applyAuthState, 1500);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', onReady);
  } else {
    onReady();
  }
})();
