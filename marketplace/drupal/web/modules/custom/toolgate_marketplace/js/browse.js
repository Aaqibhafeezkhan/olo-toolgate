// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//
// Browse filters without a full page reload. The page works without this
// script: filters are plain links and a GET form.
((Drupal, once) => {
  async function load(url, push) {
    const results = document.querySelector('[data-tgm-browse]');
    results.setAttribute('aria-busy', 'true');
    try {
      const response = await fetch(url, { headers: { Accept: 'text/html' }, credentials: 'same-origin' });
      if (!response.ok) {
        window.location.assign(url);
        return;
      }
      const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
      const fresh = doc.querySelector('[data-tgm-browse]');
      if (!fresh) {
        window.location.assign(url);
        return;
      }
      results.replaceWith(fresh);
      if (push) {
        window.history.pushState({ tgm: true }, '', url);
      }
      Drupal.attachBehaviors(fresh.parentNode);
      fresh.querySelector('[data-tgm-results]')?.focus?.();
    } catch (e) {
      window.location.assign(url);
    }
  }

  Drupal.behaviors.tgmBrowse = {
    attach(context) {
      once('tgm-browse', '[data-tgm-browse]', context).forEach((root) => {
        root.addEventListener('click', (event) => {
          const link = event.target.closest('a[data-tgm-ajax], .pager a');
          if (!link || event.metaKey || event.ctrlKey || event.shiftKey) {
            return;
          }
          event.preventDefault();
          load(link.href, true);
        });
        const form = root.querySelector('[data-tgm-filters]');
        form?.addEventListener('submit', (event) => {
          event.preventDefault();
          const params = new URLSearchParams(new FormData(form));
          [...params.keys()].forEach((key) => { if (!params.get(key)) params.delete(key); });
          load(`${form.action}?${params}`, true);
        });
        form?.querySelector('select[name="sort"]')?.addEventListener('change', () => form.requestSubmit());
        const toggle = root.querySelector('[data-tgm-facets-toggle]');
        toggle?.addEventListener('click', () => {
          const open = toggle.getAttribute('aria-expanded') !== 'true';
          toggle.setAttribute('aria-expanded', String(open));
          root.querySelector('.tgm-facets')?.classList.toggle('is-open', open);
        });
      });
      once('tgm-browse-history', 'body', context).forEach(() => {
        window.addEventListener('popstate', () => load(window.location.href, false));
      });
    },
  };
})(Drupal, once);
