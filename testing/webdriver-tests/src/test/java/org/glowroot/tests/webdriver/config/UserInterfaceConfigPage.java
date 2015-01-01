/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.tests.webdriver.config;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.glowroot.tests.webdriver.Utils;

import static org.openqa.selenium.By.xpath;

public class UserInterfaceConfigPage {

    private final WebDriver driver;

    public UserInterfaceConfigPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getPasswordEnabledCheckBox() {
        return withWait(xpath(".//div[@gt-model='config.passwordEnabled']//input"));
    }

    public WebElement getInitialPasswordTextField() {
        return withWait(xpath(".//input[@ng-model='page.initialPassword']"));
    }

    public WebElement getVerifyInitialPasswordTextField() {
        return withWait(xpath(".//input[@ng-model='page.verifyInitialPassword']"));
    }

    public WebElement getVerifyCurrentPasswordTextField() {
        return withWait(xpath(".//input[@ng-model='page.verifyCurrentPassword']"));
    }

    public WebElement getSaveButton() {
        return withWait(xpath("//div[@name='formCtrl']//div[@gt-label='Save changes']//button"));
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }
}
