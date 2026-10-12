<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace\Form;

use Drupal\Core\DependencyInjection\AutowireTrait;
use Drupal\Core\Form\FormBase;
use Drupal\Core\Form\FormStateInterface;
use Drupal\Core\Url;
use Drupal\toolgate_marketplace\HelperException;
use Drupal\toolgate_marketplace\MarketplaceSettings;
use Drupal\toolgate_marketplace\PublishException;
use Drupal\toolgate_marketplace\PublishService;
use Symfony\Component\HttpFoundation\File\UploadedFile;
use Symfony\Component\HttpFoundation\RedirectResponse;

/**
 * Step 1 of the publish wizard: upload, inspect, check ownership, sign.
 */
final class UploadForm extends FormBase {

  use AutowireTrait;

  public function __construct(
    private readonly PublishService $publishing,
    private readonly MarketplaceSettings $settings,
  ) {}

  public function getFormId(): string {
    return 'toolgate_marketplace_upload';
  }

  public function buildForm(array $form, FormStateInterface $form_state): array|RedirectResponse {
    $publisher = $this->publishing->publisherFor($this->currentUser());
    if (!$publisher) {
      $this->messenger()->addWarning($this->t('Create your publisher profile first. Its slug becomes your package-id prefix.'));
      return new RedirectResponse(Url::fromRoute('toolgate_marketplace.my_profile')->toString());
    }
    $form['#attributes']['class'][] = 'tgm-form';
    $form['steps'] = ['#theme' => 'tgm_steps', '#current' => 1];
    $form['intro'] = [
      '#markup' => '<p>' . $this->t('Upload a <code>.tgpkg</code> built with <code>toolgate pack</code>. Its package id must start with <code>@slug.</code>. We check the format and sign it; nothing is public until you describe it and publish.', ['@slug' => $publisher->slug()]) . '</p>',
    ];
    $form['package'] = [
      '#type' => 'file',
      '#title' => $this->t('Package file'),
      '#required' => TRUE,
      '#attributes' => ['accept' => '.tgpkg'],
      '#description' => $this->t('At most @mb MB.', ['@mb' => $this->settings->get('max_package_mb')]),
    ];
    $form['actions'] = ['#type' => 'actions', 'submit' => ['#type' => 'submit', '#value' => $this->t('Upload and check'), '#button_type' => 'primary']];
    $form['#cache']['contexts'][] = 'user';
    return $form;
  }

  private function uploaded(): ?UploadedFile {
    $files = $this->getRequest()->files->get('files', []);
    $file = $files['package'] ?? NULL;
    return $file instanceof UploadedFile ? $file : NULL;
  }

  public function validateForm(array &$form, FormStateInterface $form_state): void {
    $file = $this->uploaded();
    if (!$file || !$file->isValid()) {
      $form_state->setErrorByName('package', $this->t('Choose a package file. It may be larger than the upload limit.'));
      return;
    }
    if (!str_ends_with(strtolower($file->getClientOriginalName()), '.tgpkg')) {
      $form_state->setErrorByName('package', $this->t('Upload a .tgpkg package file.'));
    }
    elseif ($file->getSize() > $this->settings->maxPackageBytes()) {
      $form_state->setErrorByName('package', $this->t('Packages can be at most @mb MB.', ['@mb' => $this->settings->get('max_package_mb')]));
    }
  }

  public function submitForm(array &$form, FormStateInterface $form_state): void {
    $file = $this->uploaded();
    try {
      $version = $this->publishing->upload($file->getRealPath(), $file->getClientOriginalName());
    }
    catch (HelperException | PublishException $e) {
      $this->messenger()->addError($e->retryable
        ? $this->t('@message Nothing was published; try again.', ['@message' => $e->getMessage()])
        : $e->getMessage());
      return;
    }
    $form_state->setRedirect('toolgate_marketplace.publish_checks', ['tgm_version' => $version->id()]);
  }

}
