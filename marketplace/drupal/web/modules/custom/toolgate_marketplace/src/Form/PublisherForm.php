<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace\Form;

use Drupal\Core\DependencyInjection\AutowireTrait;
use Drupal\Core\Form\FormBase;
use Drupal\Core\Form\FormStateInterface;
use Drupal\toolgate_marketplace\Entity\Publisher;
use Drupal\toolgate_marketplace\Markdown;
use Drupal\toolgate_marketplace\PublishException;
use Drupal\toolgate_marketplace\PublishService;

/**
 * Create or edit the signed-in account's own publisher profile.
 *
 * The slug is asked for only when the profile is created; afterwards it is
 * shown, never editable (design §4.1).
 */
final class PublisherForm extends FormBase {

  use AutowireTrait;

  public function __construct(private readonly PublishService $publishing) {}

  public function getFormId(): string {
    return 'toolgate_marketplace_publisher';
  }

  public function buildForm(array $form, FormStateInterface $form_state): array {
    $publisher = $this->publishing->publisherFor($this->currentUser());
    $value = static fn(string $field) => $publisher?->get($field)->value;
    $form['#attributes']['class'][] = 'tgm-form';
    $form['intro'] = ['#markup' => '<p class="tgm-note">' . $this->t('Everything on this form is public, except where noted. Your login email is never shown.') . '</p>'];
    if ($publisher) {
      $form['slug_display'] = [
        '#type' => 'item',
        '#title' => $this->t('Slug'),
        '#markup' => '<code>' . $publisher->slug() . '</code>',
        '#description' => $this->t('Your package ids start with "@slug.". The slug can never be changed.', ['@slug' => $publisher->slug()]),
      ];
    }
    else {
      $form['slug'] = [
        '#type' => 'textfield',
        '#title' => $this->t('Slug'),
        '#required' => TRUE,
        '#maxlength' => 31,
        '#description' => $this->t('Your package-id prefix and profile URL, for example "acme" for acme.notify. 2 to 31 lowercase letters, digits or hyphens. <strong>It can never be changed.</strong>'),
        '#attributes' => ['pattern' => '[a-z][a-z0-9-]{1,30}', 'autocomplete' => 'off'],
      ];
    }
    $form['display_name'] = ['#type' => 'textfield', '#title' => $this->t('Display name'), '#required' => TRUE, '#maxlength' => 80, '#default_value' => $value('display_name')];
    $form['organization'] = ['#type' => 'textfield', '#title' => $this->t('Organization'), '#maxlength' => 120, '#default_value' => $value('organization'), '#description' => $this->t('Shown as you enter it; not verified.')];
    $form['bio'] = ['#type' => 'textarea', '#title' => $this->t('Bio'), '#rows' => 4, '#default_value' => $value('bio'), '#description' => $this->t('Markdown. Links must be https://.')];
    $form['avatar'] = [
      '#type' => 'managed_file',
      '#title' => $this->t('Avatar'),
      '#upload_location' => 'private://toolgate/avatars',
      '#upload_validators' => [
        'FileExtension' => ['extensions' => 'png jpg jpeg'],
        'FileSizeLimit' => ['fileLimit' => 1024 * 1024],
        'FileIsImage' => [],
        'FileImageDimensions' => ['maxDimensions' => '1024x1024', 'minDimensions' => '64x64'],
      ],
      '#default_value' => $publisher?->get('avatar')->target_id ? [$publisher->get('avatar')->target_id] : NULL,
      '#description' => $this->t('PNG or JPEG, 64 to 1024 pixels, at most 1 MB.'),
    ];
    foreach (['website_url' => $this->t('Website'), 'github_url' => $this->t('GitHub'), 'linkedin_url' => $this->t('LinkedIn')] as $field => $label) {
      $form[$field] = ['#type' => 'url', '#title' => $label, '#maxlength' => 255, '#default_value' => $value($field), '#attributes' => ['placeholder' => 'https://']];
    }
    $form['country'] = ['#type' => 'select', '#title' => $this->t('Country'), '#options' => \Drupal::service('country_manager')->getList(), '#empty_option' => $this->t('- Not shown -'), '#default_value' => $value('country')];
    $form['public_contact_email'] = [
      '#type' => 'email',
      '#title' => $this->t('Public contact email'),
      '#default_value' => $value('public_contact_email'),
      '#description' => $this->t('Optional and public. Leave empty to show no email.'),
    ];
    $form['actions'] = ['#type' => 'actions', 'submit' => ['#type' => 'submit', '#value' => $publisher ? $this->t('Save profile') : $this->t('Create profile'), '#button_type' => 'primary']];
    $form['#cache']['contexts'][] = 'user';
    return $form;
  }

  public function validateForm(array &$form, FormStateInterface $form_state): void {
    if (!$this->publishing->publisherFor($this->currentUser())) {
      $slug = strtolower(trim((string) $form_state->getValue('slug')));
      if ($problem = $this->publishing->slugProblem($slug)) {
        $form_state->setErrorByName('slug', $problem);
      }
    }
    foreach (['website_url', 'github_url', 'linkedin_url'] as $field) {
      $url = trim((string) $form_state->getValue($field));
      if ($url !== '' && !Markdown::isHttps($url)) {
        $form_state->setErrorByName($field, $this->t('Use an https:// address.'));
      }
    }
  }

  public function submitForm(array &$form, FormStateInterface $form_state): void {
    $values = [];
    foreach (['display_name', 'organization', 'bio', 'website_url', 'github_url', 'linkedin_url', 'country', 'public_contact_email'] as $field) {
      $value = trim((string) $form_state->getValue($field));
      $values[$field] = $value === '' ? NULL : $value;
    }
    $values['avatar'] = $form_state->getValue('avatar')[0] ?? NULL;
    $publisher = $this->publishing->publisherFor($this->currentUser());
    try {
      if (!$publisher) {
        $publisher = $this->publishing->createPublisher($this->currentUser(), strtolower(trim((string) $form_state->getValue('slug'))), $values);
      }
      else {
        foreach ($values as $field => $value) {
          $publisher->set($field, $value);
        }
        $publisher->save();
      }
    }
    catch (PublishException $e) {
      $this->messenger()->addError($e->getMessage());
      $form_state->setRebuild();
      return;
    }
    if ($publisher->get('avatar')->target_id) {
      $file = $publisher->get('avatar')->entity;
      if ($file && !$file->isPermanent()) {
        $file->setPermanent();
        $file->save();
        \Drupal::service('file.usage')->add($file, 'toolgate_marketplace', 'tgm_publisher', $publisher->id());
      }
    }
    $this->messenger()->addStatus($this->t('Your publisher profile is saved.'));
    $form_state->setRedirect('toolgate_marketplace.my_packages');
  }

}
