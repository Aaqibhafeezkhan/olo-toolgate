<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace\Form;

use Drupal\Core\DependencyInjection\AutowireTrait;
use Drupal\Core\Form\FormBase;
use Drupal\Core\Form\FormStateInterface;
use Drupal\toolgate_marketplace\Entity\Version;
use Drupal\toolgate_marketplace\Markdown;
use Drupal\toolgate_marketplace\PublishException;
use Drupal\toolgate_marketplace\PublishService;
use Symfony\Component\HttpKernel\Exception\AccessDeniedHttpException;

/**
 * Steps 3 and 4 of the wizard (describe, publish), and later listing edits.
 *
 * Only display fields are editable here; the package id, version, tools,
 * settings and digests come from the signed package and are shown read-only.
 */
final class ListingForm extends FormBase {

  use AutowireTrait;

  public function __construct(private readonly PublishService $publishing) {}

  public function getFormId(): string {
    return 'toolgate_marketplace_listing';
  }

  public function buildForm(array $form, FormStateInterface $form_state, ?Version $tgm_version = NULL): array {
    if (!$tgm_version || !in_array($tgm_version->status(), [Version::VALIDATED, Version::AVAILABLE, Version::DELISTED], TRUE)) {
      throw new AccessDeniedHttpException();
    }
    $form_state->set('version_id', $tgm_version->id());
    $draft = $tgm_version->status() === Version::VALIDATED;
    $form['#attributes']['class'][] = 'tgm-form';
    if ($draft) {
      $form['steps'] = ['#theme' => 'tgm_steps', '#current' => 3];
    }
    $form['package'] = [
      '#type' => 'item',
      '#title' => $this->t('From the package (can’t be edited)'),
      '#markup' => '<dl class="tgm-facts"><dt>' . $this->t('Package id') . '</dt><dd><code>' . $tgm_version->packageId() . '</code></dd><dt>' . $this->t('Version') . '</dt><dd><code>' . $tgm_version->version() . '</code></dd><dt>' . $this->t('Runtime') . '</dt><dd>' . $tgm_version->get('runtime')->value . '</dd><dt>' . $this->t('Package digest') . '</dt><dd><code>' . $tgm_version->get('package_digest')->value . '</code></dd></dl>',
    ];
    $value = static fn(string $field) => $tgm_version->get($field)->value;
    $form['title'] = ['#type' => 'textfield', '#title' => $this->t('Title'), '#required' => TRUE, '#maxlength' => 120, '#default_value' => $value('title')];
    $form['summary'] = ['#type' => 'textfield', '#title' => $this->t('One-line summary'), '#maxlength' => 280, '#default_value' => $value('summary')];
    $form['description'] = ['#type' => 'textarea', '#title' => $this->t('Description'), '#rows' => 12, '#default_value' => $value('description'), '#description' => $this->t('Markdown. HTML and images are removed; only https:// links are kept.')];
    $form['category'] = ['#type' => 'select', '#title' => $this->t('Category'), '#options' => array_combine(PublishService::CATEGORIES, PublishService::CATEGORIES), '#empty_option' => $this->t('- Choose -'), '#required' => TRUE, '#default_value' => $value('category')];
    $form['tags'] = ['#type' => 'textfield', '#title' => $this->t('Tags'), '#maxlength' => 512, '#default_value' => $value('tags'), '#description' => $this->t('Comma-separated, up to 10.')];
    $form['repository_url'] = ['#type' => 'url', '#title' => $this->t('Source repository'), '#default_value' => $value('repository_url'), '#attributes' => ['placeholder' => 'https://']];
    $form['documentation_url'] = ['#type' => 'url', '#title' => $this->t('Documentation'), '#default_value' => $value('documentation_url'), '#attributes' => ['placeholder' => 'https://']];
    $form['license'] = ['#type' => 'textfield', '#title' => $this->t('License'), '#maxlength' => 64, '#default_value' => $value('license'), '#description' => $this->t('An SPDX expression, for example Apache-2.0.')];
    $form['screenshots'] = [
      '#type' => 'managed_file',
      '#title' => $this->t('Screenshots'),
      '#multiple' => TRUE,
      '#upload_location' => 'private://toolgate/screenshots',
      '#upload_validators' => [
        'FileExtension' => ['extensions' => 'png jpg jpeg'],
        'FileSizeLimit' => ['fileLimit' => 1024 * 1024],
        'FileIsImage' => [],
        'FileImageDimensions' => ['maxDimensions' => '2560x1600'],
      ],
      '#default_value' => array_column($tgm_version->get('screenshots')->getValue(), 'target_id'),
      '#description' => $this->t('Up to 4 PNG or JPEG images, at most 1 MB each.'),
    ];
    $form['disclaimer'] = ['#markup' => '<p class="tgm-note">' . $this->t('Publisher details are supplied by the publisher. Validation checks package format, not code safety.') . '</p>'];
    $form['actions'] = ['#type' => 'actions', 'submit' => ['#type' => 'submit', '#value' => $draft ? $this->t('Publish') : $this->t('Save listing'), '#button_type' => 'primary']];
    $form['#cache'] = ['max-age' => 0];
    return $form;
  }

  public function validateForm(array &$form, FormStateInterface $form_state): void {
    foreach (['repository_url', 'documentation_url'] as $field) {
      $url = trim((string) $form_state->getValue($field));
      if ($url !== '' && !Markdown::isHttps($url)) {
        $form_state->setErrorByName($field, $this->t('Use an https:// address.'));
      }
    }
    $tags = array_filter(array_map('trim', explode(',', (string) $form_state->getValue('tags'))));
    if (count($tags) > 10) {
      $form_state->setErrorByName('tags', $this->t('Use at most 10 tags.'));
    }
    if (count((array) $form_state->getValue('screenshots')) > 4) {
      $form_state->setErrorByName('screenshots', $this->t('Add at most 4 screenshots.'));
    }
  }

  public function submitForm(array &$form, FormStateInterface $form_state): void {
    /** @var \Drupal\toolgate_marketplace\Entity\Version $version */
    $version = \Drupal::entityTypeManager()->getStorage('tgm_version')->load($form_state->get('version_id'));
    $listing = [];
    foreach (['title', 'summary', 'description', 'category', 'repository_url', 'documentation_url', 'license'] as $field) {
      $value = trim((string) $form_state->getValue($field));
      $listing[$field] = $value === '' ? NULL : $value;
    }
    $tags = array_values(array_unique(array_filter(array_map(static fn($t) => mb_substr(trim($t), 0, 40), explode(',', (string) $form_state->getValue('tags'))))));
    $listing['tags'] = implode(', ', $tags);
    $screenshots = array_values(array_filter((array) $form_state->getValue('screenshots')));
    $listing['screenshots'] = array_map(static fn($fid) => ['target_id' => $fid, 'alt' => $listing['title'] ?? ''], $screenshots);
    $draft = $version->status() === Version::VALIDATED;
    try {
      $version = $draft
        ? $this->publishing->publish($version, $listing)
        : $this->publishing->updateListing($version, $listing);
    }
    catch (PublishException $e) {
      $this->messenger()->addError($e->getMessage());
      return;
    }
    foreach ($version->get('screenshots') as $item) {
      if ($item->entity && !$item->entity->isPermanent()) {
        $item->entity->setPermanent();
        $item->entity->save();
      }
    }
    $this->messenger()->addStatus($draft
      ? $this->t('@id @version is published.', ['@id' => $version->packageId(), '@version' => $version->version()])
      : $this->t('The listing is saved.'));
    $form_state->setRedirect('toolgate_marketplace.tool_version', ['package_id' => $version->packageId(), 'version' => $version->version()]);
  }

}
