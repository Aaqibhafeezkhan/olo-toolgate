// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
((Drupal, once) => {
  Drupal.behaviors.tgmTool = {
    attach(context) {
      once('tgm-version-picker', '[data-tgm-version-picker]', context).forEach((select) => {
        select.addEventListener('change', () => window.location.assign(select.value));
      });
      once('tgm-copy', '[data-tgm-copy]', context).forEach((button) => {
        const box = button.parentNode.querySelector('[data-tgm-copy-source]');
        const status = button.parentNode.querySelector('[data-tgm-copy-status]');
        button.addEventListener('click', async () => {
          try {
            await navigator.clipboard.writeText(box.textContent.trim());
            status.textContent = Drupal.t('Copied.');
          } catch (e) {
            status.textContent = Drupal.t('Select the text and copy it.');
          }
        });
      });
    },
  };
})(Drupal, once);
