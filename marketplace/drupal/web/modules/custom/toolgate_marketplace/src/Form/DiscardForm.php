<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace\Form;

use Drupal\Core\DependencyInjection\AutowireTrait;
use Drupal\Core\Form\ConfirmFormBase;
use Drupal\Core\Form\FormStateInterface;
use Drupal\Core\StringTranslation\TranslatableMarkup;
use Drupal\Core\Url;
use Drupal\toolgate_marketplace\Entity\Version;
use Drupal\toolgate_marketplace\PublishException;
use Drupal\toolgate_marketplace\PublishService;

/**
 * Deletes an unpublished draft or a rejected upload.
 */
final class DiscardForm extends ConfirmFormBase {

  use AutowireTrait;

  public function __construct(private readonly PublishService $publishing) {}

  public function getFormId(): string {
    return 'toolgate_marketplace_discard';
  }

  public function getQuestion(): TranslatableMarkup {
    return $this->t('Discard this upload?');
  }

  public function getDescription(): TranslatableMarkup {
    return $this->t('The uploaded file and its signature are deleted. The version number becomes free again.');
  }

  public function getCancelUrl(): Url {
    return Url::fromRoute('toolgate_marketplace.my_packages');
  }

  public function buildForm(array $form, FormStateInterface $form_state, ?Version $tgm_version = NULL): array {
    $form_state->set('version_id', $tgm_version?->id());
    return parent::buildForm($form, $form_state);
  }

  public function submitForm(array &$form, FormStateInterface $form_state): void {
    $version = \Drupal::entityTypeManager()->getStorage('tgm_version')->load($form_state->get('version_id'));
    try {
      $this->publishing->discard($version);
      $this->messenger()->addStatus($this->t('The upload is discarded.'));
    }
    catch (PublishException $e) {
      $this->messenger()->addError($e->getMessage());
    }
    $form_state->setRedirectUrl($this->getCancelUrl());
  }

}
