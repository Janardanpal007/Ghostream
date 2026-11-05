(() => {
  'use strict';
  const SCREEN_DIR = 'screenshots/web/';
  const DEFAULT_ANGLE = '-15';
  const RELEASE_BASE = 'https://github.com/Janardanpal007/Ghostream/releases/download/v1.3.8/';
  const features = {
    browse: { number:'01 / BROWSE', title:'Browse', image:'browse.webp', alt:'Ghostream browsing NASA with the shield in the address bar', description:'Tabs, bookmarks and your shield. Right where you need them.' },
    shield: { number:'02 / BLOCK', title:'Shields & privacy', image:'privacy.webp', alt:'Ghostream Shields and privacy settings showing ad blocking, HTTPS upgrades and cookie controls', description:'Open filter lists, HTTPS upgrades and cookie controls. Your settings, your choice.' },
    download: { number:'03 / DOWNLOAD', title:'Downloads', image:'download-progress.webp', alt:'An actual Ghostream download in progress with pause and cancel controls', description:'Up to 8 connections per file, with pause and resume. Actual speeds depend on your connection.' },
    reader: { number:'04 / READ', title:'Reader mode', image:'reader.webp', alt:'Ghostream reader mode showing an article as clean, readable text', description:'Just the article, with clean text and adjustable type. A little room to focus.' }
  };
  const releases = {
    arm64: { label:'ARM64', file:'Ghostream-1.3.8-android-arm64.apk', hash:'db5c7382808a1898b613e70a4df1b16f08634f409f44be43b55dd39662817bd0' },
    universal: { label:'Universal', file:'Ghostream-1.3.8-android-universal.apk', hash:'d794952345e2b046a4a39756070828dda527e14ee2a93ff93aef7682783fa5b6' }
  };
  const $ = (s) => document.querySelector(s);
  const tabs = [...document.querySelectorAll('[role="tab"]')];
  const screen = $('#app-screen');
  const phone = $('#phone');
  const angle = $('#view-angle');
  const dialog = $('#screen-dialog');
  const menu = $('.menu-toggle');
  const mobileNav = $('#mobile-nav');
  let current = 'browse';

  function chooseFeature(key, focus = false) {
    const f = features[key];
    if (!f) return;
    current = key;
    screen.src = SCREEN_DIR + f.image;
    screen.alt = f.alt;
    $('#screen-number').textContent = f.number;
    $('#screen-description').textContent = f.description;
    $('#screen-panel').setAttribute('aria-labelledby', 'tab-' + key);
    $('#screen-button').setAttribute('aria-label', 'Enlarge the ' + f.title.toLowerCase() + ' screenshot');
    tabs.forEach(t => {
      const active = t.dataset.feature === key;
      t.setAttribute('aria-selected', String(active));
      t.tabIndex = active ? 0 : -1;
    });
    if (focus) $('#tab-' + key).focus({ preventScroll: true });
  }

  function setupTabs() {
    document.querySelectorAll('[data-feature]').forEach(el => el.addEventListener('click', () => chooseFeature(el.dataset.feature)));
    const keyTargets = { ArrowRight: i => (i + 1) % tabs.length, ArrowLeft: i => (i + tabs.length - 1) % tabs.length, Home: () => 0, End: () => tabs.length - 1 };
    tabs.forEach((tab, i) => tab.addEventListener('keydown', e => {
      const target = keyTargets[e.key];
      if (!target) return;
      e.preventDefault();
      chooseFeature(tabs[target(i)].dataset.feature, true);
    }));
  }

  const updateAngle = () => {
    phone.style.setProperty('--angle', angle.value + 'deg');
    angle.setAttribute('aria-valuetext', angle.value + ' degrees');
  };

  let drag = null;
  let dragMoved = false;
  function setupRotation() {
    angle.addEventListener('input', updateAngle);
    $('.reset-angle').addEventListener('click', () => { angle.value = DEFAULT_ANGLE; updateAngle(); });
    phone.addEventListener('pointerdown', e => {
      if (e.button !== 0) return;
      e.preventDefault(); // stop native image drag, which would cancel the rotation
      drag = { x: e.clientX, y: e.clientY, start: Number(angle.value) };
      dragMoved = false;
    });
    window.addEventListener('pointermove', e => {
      if (!drag) return;
      const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
      if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 10 && !dragMoved) { drag = null; return; }
      if (Math.abs(dx) > 7) dragMoved = true;
      if (dragMoved) { angle.value = String(Math.max(-30, Math.min(30, drag.start + dx / 3))); updateAngle(); }
    }, { passive: true });
    window.addEventListener('pointerup', () => { drag = null; });
    window.addEventListener('pointercancel', () => { drag = null; });
  }

  function setupDialog() {
    $('#screen-button').addEventListener('click', e => {
      if (dragMoved) { e.preventDefault(); dragMoved = false; return; }
      const f = features[current];
      $('#dialog-title').textContent = f.title + ' — app screenshot';
      $('#dialog-image').src = SCREEN_DIR + f.image;
      $('#dialog-image').alt = f.alt;
      dialog.showModal();
    });
    $('.close-dialog').addEventListener('click', () => dialog.close());
    dialog.addEventListener('click', e => {
      if (e.target !== dialog) return;
      const r = dialog.getBoundingClientRect();
      if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) dialog.close();
    });
  }

  function setMenu(open) {
    menu.setAttribute('aria-expanded', String(open));
    menu.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
    mobileNav.hidden = !open;
  }

  function setupMenu() {
    menu.addEventListener('click', () => setMenu(menu.getAttribute('aria-expanded') !== 'true'));
    mobileNav.querySelectorAll('a').forEach(a => a.addEventListener('click', () => setMenu(false)));
    document.addEventListener('keydown', e => {
      if (e.key === 'Escape' && !mobileNav.hidden) { setMenu(false); menu.focus(); }
    });
  }

  function setupDownloads() {
    document.querySelectorAll('input[name="apk"]').forEach(r => r.addEventListener('change', () => {
      const a = releases[r.value];
      $('#apk-download').href = RELEASE_BASE + a.file;
      $('#download-label').textContent = 'Download ' + a.label + ' APK';
      $('#checksum').textContent = a.hash;
      $('#copy-status').textContent = '';
    }));
    $('.copy-checksum').addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText($('#checksum').textContent);
        $('#copy-status').textContent = 'SHA-256 copied.';
      } catch {
        $('#copy-status').textContent = 'Select and copy the SHA-256 shown above.';
      }
    });
  }

  function setupMotion() {
    const motionButton = $('.motion-toggle');
    const motionPreference = window.matchMedia('(prefers-reduced-motion: reduce)');
    function setMotion(paused) {
      document.body.classList.toggle('motion-paused', paused);
      motionButton.setAttribute('aria-pressed', String(paused));
      motionButton.textContent = paused ? 'Play motion' : 'Pause motion';
      motionButton.disabled = motionPreference.matches;
      if (motionPreference.matches) motionButton.textContent = 'Reduced motion';
    }
    motionButton.addEventListener('click', () => setMotion(!document.body.classList.contains('motion-paused')));
    motionPreference.addEventListener('change', () => setMotion(motionPreference.matches));
    setMotion(motionPreference.matches);
    document.querySelectorAll('.parts-bars i').forEach((bar, i) => bar.style.setProperty('--part', i));
  }

  setupTabs();
  setupRotation();
  setupDialog();
  setupMenu();
  setupDownloads();
  setupMotion();
  updateAngle();
  // Preload the other app screens once the page has loaded.
  window.addEventListener('load', () => {
    Object.values(features).forEach(f => { const img = new Image(); img.src = SCREEN_DIR + f.image; });
  });
})();
