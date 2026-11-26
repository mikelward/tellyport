package tv.tellyport.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.tellyport.location.MockLocationPermissionStatus
import tv.tellyport.ui.theme.TellyportColors

/**
 * Primary focusable action button supporting both Android TV D-Pad navigation and touch.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TellyportPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: Color = TellyportColors.Primary,
    contentColor: Color = TellyportColors.OnPrimary,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ButtonDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = Color.White,
            focusedContentColor = TellyportColors.Background
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.5.dp, TellyportColors.FocusRing),
                shape = RoundedCornerShape(10.dp)
            )
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.04f)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Secondary outlined focusable button for D-Pad and touch.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TellyportSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    borderColor: Color = TellyportColors.BorderSubtle,
    contentColor: Color = TellyportColors.TextPrimary,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        shape = OutlinedButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = OutlinedButtonDefaults.colors(
            containerColor = TellyportColors.SurfaceElevated,
            contentColor = contentColor,
            focusedContainerColor = TellyportColors.SurfaceFocused,
            focusedContentColor = Color.White
        ),
        border = OutlinedButtonDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, borderColor),
                shape = RoundedCornerShape(10.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.5.dp, TellyportColors.FocusRing),
                shape = RoundedCornerShape(10.dp)
            )
        ),
        scale = OutlinedButtonDefaults.scale(focusedScale = 1.04f)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Focusable chip used for presets and backend toggles on TV & mobile.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TellyportSelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) TellyportColors.InfoContainer else TellyportColors.SurfaceElevated,
            contentColor = if (selected) TellyportColors.Primary else TellyportColors.TextSecondary,
            focusedContainerColor = TellyportColors.SurfaceFocused,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) TellyportColors.Primary else TellyportColors.BorderSubtle
                ),
                shape = RoundedCornerShape(8.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.5.dp, TellyportColors.FocusRing),
                shape = RoundedCornerShape(8.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            maxLines = 1
        )
    }
}

/**
 * Compact status indicator pill (e.g., VPN Connected / Mock Location Active).
 */
@Composable
fun StatusPill(
    label: String,
    active: Boolean,
    activeColor: Color = TellyportColors.Success,
    activeContainer: Color = TellyportColors.SuccessContainer,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) activeContainer else TellyportColors.SurfaceElevated)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (active) activeColor else TellyportColors.TextMuted)
        )
        Text(
            text = label,
            color = if (active) TellyportColors.TextPrimary else TellyportColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Small informational badge for VPN backend or Target App on profile cards.
 */
@Composable
fun InfoBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Prominent setup banner shown when Tellyport is missing mock location authorization or
 * runtime location permission, with Developer Options deep link and ADB one-liner.
 */
@Composable
fun MockLocationSetupBanner(
    permissionStatus: MockLocationPermissionStatus,
    onOpenDeveloperOptions: () -> Unit,
    onCopyAdbCommand: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(TellyportColors.WarningContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = TellyportColors.Warning,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (!permissionStatus.isMockLocationAppSelected) {
                        "Mock Location App Setup Required"
                    } else {
                        "Location Permission Required"
                    },
                    color = TellyportColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (!permissionStatus.isMockLocationAppSelected) {
                        "Select Tellyport under Developer Options → Select mock location app, or run the ADB command below:"
                    } else {
                        "Android 14 requires Location permission to run the foreground mock location service."
                    },
                    color = TellyportColors.TextSecondary,
                    fontSize = 13.sp
                )
            }
        }

        if (!permissionStatus.isMockLocationAppSelected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TellyportColors.Background)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = permissionStatus.adbCommand,
                    color = TellyportColors.Primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!permissionStatus.hasFineLocationPermission) {
                TellyportPrimaryButton(
                    text = "Grant Location",
                    icon = Icons.Rounded.MyLocation,
                    onClick = onRequestLocationPermission,
                    containerColor = TellyportColors.Warning,
                    contentColor = TellyportColors.Background
                )
            }
            if (!permissionStatus.isMockLocationAppSelected) {
                TellyportPrimaryButton(
                    text = "Developer Options",
                    icon = Icons.Rounded.DeveloperMode,
                    onClick = onOpenDeveloperOptions,
                    containerColor = TellyportColors.Primary,
                    contentColor = TellyportColors.OnPrimary
                )
                TellyportSecondaryButton(
                    text = "Copy ADB",
                    icon = Icons.Rounded.ContentCopy,
                    onClick = onCopyAdbCommand
                )
            }
        }
    }
}
