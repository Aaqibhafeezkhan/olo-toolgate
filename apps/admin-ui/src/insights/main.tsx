// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { InsightsApp } from './InsightsApp';
import '../style.css';
import './insights.css';
import { applyTheme, themePreference } from '../Theme';

applyTheme(themePreference());
createRoot(document.getElementById('root')!).render(<StrictMode><InsightsApp /></StrictMode>);
