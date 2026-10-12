<?php

// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0

namespace Drupal\toolgate_marketplace;

use Drupal\Component\Utility\Html;
use Drupal\Component\Utility\Xss;
use League\CommonMark\GithubFlavoredMarkdownConverter;

/**
 * Renders publisher Markdown and package READMEs safely (design §5.3).
 *
 * Raw HTML in the source is dropped, only https:// links survive and get
 * rel="nofollow noopener noreferrer ugc", images are removed (no remote
 * fetches from visitors' browsers), and the result passes Drupal's XSS
 * allowlist as a second layer.
 */
final class Markdown {

  private const ALLOWED_TAGS = ['a', 'p', 'br', 'em', 'strong', 'code', 'pre', 'blockquote', 'ul', 'ol', 'li', 'h2', 'h3', 'h4', 'h5', 'h6', 'hr', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'del'];

  public static function toHtml(string $markdown): string {
    $converter = new GithubFlavoredMarkdownConverter([
      'html_input' => 'strip',
      'allow_unsafe_links' => FALSE,
      'max_nesting_level' => 20,
      'max_delimiters_per_line' => 200,
    ]);
    $html = (string) $converter->convert(mb_substr($markdown, 0, 100000));

    $dom = Html::load($html);
    foreach (iterator_to_array($dom->getElementsByTagName('img')) as $img) {
      $img->parentNode->removeChild($img);
    }
    // A README's own top heading duplicates the page title: demote h1.
    foreach (iterator_to_array($dom->getElementsByTagName('h1')) as $h1) {
      $h2 = $dom->createElement('h2');
      while ($h1->firstChild) {
        $h2->appendChild($h1->firstChild);
      }
      $h1->parentNode->replaceChild($h2, $h1);
    }
    foreach (iterator_to_array($dom->getElementsByTagName('a')) as $a) {
      $href = trim($a->getAttribute('href'));
      foreach (iterator_to_array($a->attributes) as $attribute) {
        $a->removeAttribute($attribute->name);
      }
      if (self::isHttps($href)) {
        $a->setAttribute('href', $href);
        $a->setAttribute('rel', 'nofollow noopener noreferrer ugc');
      }
      else {
        while ($a->firstChild) {
          $a->parentNode->insertBefore($a->firstChild, $a);
        }
        $a->parentNode->removeChild($a);
      }
    }
    return Xss::filter(Html::serialize($dom), self::ALLOWED_TAGS);
  }

  /**
   * The first paragraph as plain text, for summaries.
   */
  public static function firstParagraph(string $markdown, int $max = 280): string {
    $html = self::toHtml($markdown);
    $dom = Html::load($html);
    $p = $dom->getElementsByTagName('p')->item(0);
    $text = $p ? trim(preg_replace('/\s+/', ' ', $p->textContent)) : '';
    return mb_strlen($text) > $max ? rtrim(mb_substr($text, 0, $max - 1)) . '…' : $text;
  }

  public static function isHttps(?string $url): bool {
    if ($url === NULL || $url === '' || strlen($url) > 255) {
      return FALSE;
    }
    $parts = parse_url($url);
    return is_array($parts) && ($parts['scheme'] ?? '') === 'https' && !empty($parts['host'])
      && !isset($parts['user']) && !isset($parts['pass']) && !preg_match('/[\s<>"]/', $url);
  }

}
