/*
 * java-brte2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-brte2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.wizbl.core.config.args;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class OverlayTest {

    private final Overlay overlay = new Overlay();

    @Before
    public void setOverlay() {
        overlay.setPort(22060);
    }

    @Test(expected = IllegalArgumentException.class)
    public void whenSetOutOfBoundsPort() {
        overlay.setPort(-1);
    }

    @Test
    public void getOverlay() {
        Assert.assertEquals(22060, overlay.getPort());
    }
}
