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
 * Delisting by the owner, or a takedown by an administrator (design §11).
 *
 * Downloads return 410 afterwards and the detail endpoint keeps showing the
 * version with delisted: true. Installs that already exist are untouched.
 */
final class DelistForm extends ConfirmFormBase {

  use AutowireTrait;

  private ?Version $version = NULL;

  public function __construct(private readonly PublishService $publishing) {}

  public function getFormId(): string {
    return 'toolgate_marketplace_delist';
  }

  public function getQuestion(): TranslatableMarkup {
    return $this->t('Delist @id @version?', ['@id' => $this->version?->packageId(), '@version' => $this->version?->version()]);
  }

  public function getDescription(): TranslatableMarkup {
    return $this->t('It disappears from the catalog and its download returns 410 Gone. ToolGate installations that already imported it keep working. This can’t be undone; publish a new version instead.');
  }

  public function getConfirmText(): TranslatableMarkup {
    return $this->t('Delist');
  }

  public function getCancelUrl(): Url {
    return $this->currentUser()->hasPermission('administer toolgate marketplace')
      ? Url::fromRoute('toolgate_marketplace.admin')
      : Url::fromRoute('toolgate_marketplace.my_packages');
  }

  public function buildForm(array $form, FormStateInterface $form_state, ?Version $tgm_version = NULL): array {
    $this->version = $tgm_version;
    $form_state->set('version_id', $tgm_version?->id());
    $form['reason'] = ['#type' => 'textfield', '#title' => $this->t('Reason'), '#required' => TRUE, '#maxlength' => 500, '#description' => $this->t('Kept for the audit trail; not shown publicly.')];
    return parent::buildForm($form, $form_state);
  }

  public function submitForm(array &$form, FormStateInterface $form_state): void {
    $version = \Drupal::entityTypeManager()->getStorage('tgm_version')->load($form_state->get('version_id'));
    try {
      $this->publishing->delist($version, (string) $form_state->getValue('reason'));
      $this->messenger()->addStatus($this->t('@id @version is delisted.', ['@id' => $version->packageId(), '@version' => $version->version()]));
    }
    catch (PublishException $e) {
      $this->messenger()->addError($e->getMessage());
    }
    $form_state->setRedirectUrl($this->getCancelUrl());
  }

}
